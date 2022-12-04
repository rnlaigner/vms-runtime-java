package dk.ku.di.dms.vms.modb.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({ElementType.TYPE})
@Retention(RUNTIME)
public @interface VmsIndex {

    /**
     * Inherited from {@link javax.persistence.Index}
     * (Required) The name of the index; defaults to a provider-generated name.
     */
    String name();

    /**
     * Inherited from {@link javax.persistence.Index}
     * (Required) The names of the columns to be included in the index,
     * in order.
     */
    String columnList();

    /**
     * Inherited from {@link javax.persistence.Index}
     * (Optional) Whether the index is unique.
     */
    boolean unique() default false;

    boolean range() default false;
}