package cc.cszt0.mangle.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a method, moves it into a generated class whose name and members are HMAC-SHA256 short names,
 * leaving the original method behind as a wrapper. {@code native} methods are always moved and do
 * not need this annotation.
 *
 * <p>On a class, renames the class itself to an HMAC-SHA256 short name in the default package, so
 * native code can find it, for example to throw it. Its members are not moved, so such a class
 * cannot declare {@code native} or {@code @Mangle} methods.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Mangle {
}
