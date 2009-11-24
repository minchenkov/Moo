package com.codiform.moo.translator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.mvel2.MVEL;
import org.mvel2.PropertyAccessException;

import com.codiform.moo.TranslationException;
import com.codiform.moo.annotation.Access;
import com.codiform.moo.annotation.AccessMode;
import com.codiform.moo.annotation.TranslateCollection;
import com.codiform.moo.configuration.Configuration;
import com.codiform.moo.source.TranslationSource;

/**
 * The workhorse class of Moo that does the actual work of creating and
 * populating translated instances.
 * 
 * @param <T>
 *            the destination type for the translator, the type to which all
 *            source objects will be translated
 */
public class Translator<T> {

	private Class<T> destinationClass;

	private Configuration configuration;

	/**
	 * Create a translator that will translate objects to the specified
	 * destination type, using the specified configuration.
	 * 
	 * @param destination
	 *            the destination type
	 * @param configuration
	 *            the configuration used during translation
	 */
	public Translator(Class<T> destination, Configuration configuration) {
		this.destinationClass = destination;
		this.configuration = configuration;
	}

	/**
	 * Update a destination instance from the source instance. This is the
	 * actual transfer of property values from source to destination.
	 * 
	 * @param source
	 *            the object from which the property values will be retrieved
	 * @param destination
	 *            the object to which the property values will be stored
	 * @param translationSource
	 *            if any of the sub-properties need to be translated, this will
	 *            provide those translations
	 */
	public void update(Object source, T destination,
			TranslationSource translationSource) {
		Set<Property> properties = getProperties(destination);
		for (Property item : properties) {
			updateProperty(source, destination, translationSource, item);
		}
	}

	/**
	 * It seems like this shouldn't be necessary, but ... sometimes generics
	 * defeats me. If anyone can figure out how to remove this method, send me a
	 * patch.
	 * 
	 * @see #update
	 */
	public void castAndUpdate(Object source, Object from,
			TranslationSource translationSource) {
		update(source, destinationClass.cast(from), translationSource);
	}

	/**
	 * Create a new instance of the destination class; this is the first step in
	 * creating a new translation.
	 * 
	 * @return the new instance
	 */
	public T create() {
		try {
			return destinationClass.newInstance();
		} catch (InstantiationException exception) {
			throw new TranslationException(String.format(
					"Error while instantiating %s", destinationClass),
					exception);
		} catch (IllegalAccessException exception) {
			throw new TranslationException(String.format(
					"Not allowed to instantiate %s", destinationClass),
					exception);
		}
	}

	@SuppressWarnings("unchecked")
	private Object transform(Object value, Property property,
			TranslationSource translationSource) {
		if (value == null) {
			return null;
		} else if (value instanceof Collection) {
			if (property.shouldBeTranslated()) {
				throw new TranslationException(
						"Cannot use @Translate on a collection (cannot determine internal type due to erasure); use @TranslateCollection instead.");
			}
			return transformCollection(value, property, translationSource);
		} else if (value.getClass().isArray()) {
			return transformArray((Object[]) value, property, translationSource);
		} else if (property.shouldBeTranslated()) {
			return translationSource.getTranslation(value, property.getType());
		} else {
			return value;
		}
	}

	private Object transformArray(Object[] value, Property property,
			TranslationSource translationSource) {
		Class<?> fieldType = property.getType();
		Class<?> valueType = value.getClass();

		if (valueType.isAssignableFrom(fieldType)) {
			return configuration.getArrayTranslator().defensiveCopy(value);
		} else if (fieldType.isArray()) {
			if (valueType.isAssignableFrom(fieldType.getComponentType())) {
				return configuration.getArrayTranslator().copyTo(value,
						fieldType);
			} else {
				return configuration.getArrayTranslator().translate(value,
						fieldType.getComponentType(), translationSource);
			}
		} else {
			throw new TranslationException(
					String
							.format(
									"Cannot translate from source array type %s[] to destination type %s",
									valueType.getComponentType(), fieldType
											.getName()));
		}
	}

	private Object transformCollection(Object value, Property property,
			TranslationSource translationSource) {
		return configuration.getCollectionTranslator().translate(value,
				property.getAnnotation(TranslateCollection.class),
				translationSource);
	}

	private Object getValue(Object source, String expression) {
		return MVEL.eval(expression, source);
	}

	private Set<Property> getProperties(T destination) {
		Set<Property> fields = new HashSet<Property>();
		Class<?> current = destinationClass;
		while (current != null) {
			fields.addAll(getDestinationsForClass(destination, current));
			current = current.getSuperclass();
		}
		return fields;
	}

	private Set<? extends Property> getDestinationsForClass(T destination,
			Class<?> clazz) {
		Set<Property> properties = new HashSet<Property>();
		Access access = clazz.getAnnotation(Access.class);
		AccessMode mode = access == null ? AccessMode.FIELD : access.value();
		for (Field item : clazz.getDeclaredFields()) {
			FieldProperty property = new FieldProperty(item);
			if (property.isProperty(mode)) {
				properties.add(property);
			}
		}
		for (Method item : clazz.getDeclaredMethods()) {
			MethodProperty property = new MethodProperty(item);
			if (property.isProperty(mode)) {
				properties.add(property);
			}
		}
		return properties;
	}

	private <V> void updateProperty(Object source, T destination,
			TranslationSource translationSource, Property property) {
		try {
			Object value = getValue(source, property.getTranslationExpression());
			value = transform(value, property, translationSource);
			property.setValue( destination, value );
		} catch (PropertyAccessException exception) {
			if (configuration.isSourcePropertyRequired()) {
				throw new TranslationException(
						"Could not find required source property for expression: "
								+ property.getTranslationExpression(), exception);
			}
		}
	}

}
