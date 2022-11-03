package remoter.annotations;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;


/**
 * Marks which of the type mutable
 */
@Retention(CLASS)
@Target({METHOD, PARAMETER})
public @interface MutableKotlinType {
    /**
     * is mutable kotlin collections
     */
    boolean value() default false;
}