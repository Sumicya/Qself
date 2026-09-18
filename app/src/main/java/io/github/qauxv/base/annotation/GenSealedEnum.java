package io.github.qauxv.base.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Denotes that the companion object of a sealed class should have sealed-enum
 * style utilities generated for it by the KSP processor in {@code libs/ksp}.
 * <p>
 * The generated {@code <SealedClassName>SealedEnum} object (in the same package
 * as the sealed class) provides {@code values}, {@code nameOf}, {@code ordinalOf}
 * and {@code valueOf}, together with top-level {@code values}, {@code name},
 * {@code ordinal} and {@code valueOf} extensions for the sealed class.
 * <p>
 * This is the native, in-tree replacement for the legacy third-party
 * {@code com.github.livefront.sealed-enum} library, which was only available
 * from the unreliable JitPack repository.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.CLASS)
public @interface GenSealedEnum {

}
