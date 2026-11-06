package cc.cszt0.mangle.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Moves the annotated method, or every method declared by the annotated class, into a generated
 * class whose name and members are HMAC-SHA256 short names. The original method stays behind as a
 * wrapper. {@code native} methods are always moved and do not need this annotation.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Mangle {
}
