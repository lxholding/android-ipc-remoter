package remoter.builder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Implements [IServiceConnector]
 */
class ServiceConnector private constructor(private val context: Context, private val serviceIntent: Intent) : IServiceConnector, CoroutineScope by CoroutineScope(Dispatchers.Default) {

    private val tag = "ServiceConnector${serviceIntent.action}"
    private var serviceBinder: IBinder? = null
    private val serviceMutex = Mutex()
    private var serviceBound = false
    private var serviceConnectCallback: ((IBinder) -> Unit)? = null
    private var serviceDisconnectCallback: (() -> Unit)? = null

    override suspend fun getService() =
            serviceMutex.withLock {
                serviceBinder ?: serviceConnection.connectWithService(serviceConnection).await()
            }

    override fun disconnect() {
        launch {
            serviceMutex.withLock {
                if (serviceBound) {
                    context.unbindService(serviceConnection)
                    serviceBound = false
                }
                serviceConnection.disconnectService()
            }
            serviceDisconnectCallback = null
        }
    }

    override fun onServiceDisconnect(callback: (() -> Unit)?) {
        this.serviceDisconnectCallback = callback
    }

    override fun onServiceConnect(callback: ((IBinder) -> Unit)?) {
        this.serviceConnectCallback = callback
    }

    /**
     * Service connection
     */
    private val serviceConnection = object : ServiceConnection {
        private var serviceConnectionDeferred = CompletableDeferred<IBinder>()

        override fun onServiceConnected(className: ComponentName, serviceBinder: IBinder?) {
            this@ServiceConnector.launch {
                Log.v(tag, "onServiceConnected $serviceIntent $serviceBinder")
                if (serviceBinder != null) {
                    this@ServiceConnector.serviceBinder = serviceBinder
                    serviceConnectionDeferred.complete(serviceBinder)
                    serviceConnectCallback?.invoke(serviceBinder)
                } else {
                    serviceConnectionDeferred.completeExceptionally(RuntimeException("No binder returned from service $serviceIntent"))
                }
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            context.unbindService(this)
            Log.v(tag, "onServiceDisconnected")
            this@ServiceConnector.launch {
                disconnectService()
                serviceDisconnectCallback?.invoke()
            }
        }

        suspend fun disconnectService() {
            launch {
                serviceMutex.withLock {
                    serviceBinder = null
                    serviceBound = false
                    Log.v(tag, "Service disconnected $serviceIntent")
                }
            }
        }

        suspend fun connectWithService(sConnection: ServiceConnection): CompletableDeferred<IBinder> = withContext(Dispatchers.IO) {
            serviceConnectionDeferred = CompletableDeferred()
            serviceBound = this@ServiceConnector.context.bindService(serviceIntent, sConnection, Context.BIND_AUTO_CREATE)
            if (!serviceBound) {
                serviceConnectionDeferred.completeExceptionally(RuntimeException("Service cannot be found $serviceIntent"))
            }
            Log.v(tag, "Connecting with service $serviceIntent, bound $serviceBound")
            serviceConnectionDeferred
        }
    }

    companion object {
        private val serviceConnectors = mutableMapOf<String, ServiceConnector>()

        /**
         * Returns an [IServiceConnector] for the given explicit [Intent].
         *
         * @param explicitIntent The explicit intent with [Intent.setComponent] set
         */
        fun of(context: Context, explicitIntent: Intent, suffix:String = ""): IServiceConnector {
            synchronized(serviceConnectors) {
                val key = explicitIntent.component!!.flattenToShortString() + suffix
                return serviceConnectors.getOrPut(key) { ServiceConnector(context, explicitIntent) }
            }
        }

        /**
         * Returns an [IServiceConnector] for a service that is registered with the given intent action [intentAction]
         */
        fun of(context: Context, intentAction: String, suffix:String = ""): IServiceConnector {
            return of(context, asExplicitIntent(context, Intent(intentAction)), suffix)
        }

        /**
         * Disconnects from all the services that are returned from this
         */
        fun disconnectAll() {
            synchronized(serviceConnectors) {
                serviceConnectors.values.forEach { it.disconnect() }
                serviceConnectors.clear()
            }
        }

        /**
         * Makes an implicit intent
         */
        private fun asExplicitIntent(context: Context, implicitIntent: Intent): Intent {
            val explicitIntent = Intent(implicitIntent)
            val pm = context.packageManager
            val resolveInfo = pm.queryIntentServices(implicitIntent, 0)
            if (resolveInfo != null && resolveInfo.size >= 1) {
                val serviceInfo = resolveInfo[0]
                val packageName = serviceInfo.serviceInfo.packageName
                val className = serviceInfo.serviceInfo.name
                val component = ComponentName(packageName, className)
                explicitIntent.component = component
            }
            return explicitIntent
        }
    }
}