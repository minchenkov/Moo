package com.codiform.moo.translator;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.mvel2.MVEL;
import org.mvel2.PropertyAccessException;

import com.codiform.moo.InvalidPropertyException;
import com.codiform.moo.MissingSourcePropertyException;
import com.codiform.moo.NoSourceException;
import com.codiform.moo.NothingToTranslateException;
import com.codiform.moo.TranslationInitializationException;
import com.codiform.moo.UnsupportedTranslationException;
import com.codiform.moo.annotation.Access;
import com.codiform.moo.annotation.AccessMode;
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
			TranslationSource translationSource, Map<String, Object> variables) {
		assureSource( source );
		boolean updated = false;
		Set<Property> properties = getProperties( destinationClass );
		for( Property item : properties ) {
			if( !item.isIgnored() ) {
				if( updateProperty( source, destination, translationSource,
						item,
						variables ) ) {
					updated = true;
				}
			}
		}
		if( updated == false ) {
			throw new NothingToTranslateException( source.getClass(),
					destination.getClass() );
		}
	}

	private void assureSource(Object source) {
		if( source == null ) {
			throw new NoSourceException();
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
			TranslationSource translationSource, Map<String, Object> variables) {
		update( source, destinationClass.cast( from ), translationSource,
				variables );
	}

	/**
	 * Create a new instance of the destination class; this is the first step in
	 * creating a new translation.
	 * 
	 * @return the new instance
	 */
	public T create() {
		try {
			Constructor<T> constructor = destinationClass.getDeclaredConstructor();
			constructor.setAccessible( true );
			return constructor.newInstance();
		} catch( NoSuchMethodException exception ) {
			throw new TranslationInitializationException(
					"No no-argument constructor in class "
							+ destinationClass.getName(), exception );
		} catch( InstantiationException exception ) {
			throw new TranslationInitializationException( String.format(
					"Error while instantiating %s", destinationClass ),
					exception );
		} catch( IllegalAccessException exception ) {
			throw new TranslationInitializationException( String.format(
					"Not allowed to instantiate %s", destinationClass ),
					exception );
		} catch( IllegalArgumentException exception ) {
			throw new TranslationInitializationException( String.format(
					"Error while instantiating %s", destinationClass ),
					exception );
		} catch( InvocationTargetException exception ) {
			throw new TranslationInitializationException( String.format(
					"Error thrown by constructor of %s", destinationClass ),
					exception );
		}
	}

	private Object transform(Object value, Property property,
			TranslationSource translationSource) {
		if( value == null ) {
			return null;
		} else if( property instanceof CollectionProperty ) {
			return transformCollection( value, (CollectionProperty) property,
					translationSource );
		} else if( value.getClass().isArray() ) {
			return transformArray( (Object[]) value, property,
					translationSource );
		} else if( property.shouldBeTranslated() ) {
			return translationSource.getTranslation( value, property.getType() );
		} else {
			return value;
		}
	}

	private Object transformArray(Object[] value, Property property,
			TranslationSource translationSource) {
		Class<?> fieldType = property.getType();
		Class<?> valueType = value.getClass();

		if( valueType.isAssignableFrom( fieldType ) ) {
			return configuration.getArrayTranslator().defensiveCopy( value );
		} else if( fieldType.isArray() ) {
			if( valueType.isAssignableFrom( fieldType.getComponentType() ) ) {
				return configuration.getArrayTranslator().copyTo( value,
						fieldType );
			} else {
				return configuration.getArrayTranslator().translate( value,
						fieldType.getComponentType(), translationSource );
			}
		} else {
			throw new UnsupportedTranslationException(
					String
							.format(
									"Cannot translate from source array type %s[] to destination type %s",
									valueType.getComponentType(), fieldType
											.getName() ) );
		}
	}

	private Object transformCollection(Object value,
			CollectionProperty property,
			TranslationSource translationSource) {
		return configuration.getCollectionTranslator().translate( value,
				property,
				translationSource );
	}

	private Object getValue(Object source, String expression,
			Map<String, Object> variables) {
		if( variables == null || variables.isEmpty() ) {
			return MVEL.eval( expression, source );
		} else {
			return MVEL.eval( expression, source, variables );
		}
	}

	/* package */Set<Property> getProperties(Class<T> destinationClass) {
		Map<String, Property> properties = new HashMap<String, Property>();
		Class<?> current = destinationClass;
		while( current != null ) {
			if( !shouldIgnoreClass( current ) ) {
				merge( properties, getPropertiesForClass( current ) );
			}
			current = current.getSuperclass();
		}
		return new HashSet<Property>( properties.values() );
	}

	private boolean shouldIgnoreClass(Class<?> current) {
		return current.getSimpleName().contains( "$$_javassist" );
	}

	private void merge(Map<String, Property> currentProperties,
			Set<Property> superclassProperties) {
		for( Property item : superclassProperties ) {
			if( currentProperties.containsKey( item.getName() ) ) {
				if( item.isExplicit() ) {
					if( !currentProperties.get( item.getName() ).isExplicit() ) {
						currentProperties.put( item.getName(), item );
					}
				}
			} else {
				currentProperties.put( item.getName(), item );
			}
		}
	}

	private Set<Property> getPropertiesForClass(Class<?> clazz) {
		Map<String, Property> properties = new HashMap<String, Property>();
		Access access = clazz.getAnnotation( Access.class );
		AccessMode mode = access == null ? configuration.getDefaultAccessMode()
				: access.value();
		for( Field item : clazz.getDeclaredFields() ) {
			Property property = PropertyFactory.createProperty( item, mode );
			if( property != null ) {
				properties.put( property.getName(), property );
			}
		}
		for( Method item : clazz.getDeclaredMethods() ) {
			Property property = PropertyFactory.createProperty( item, mode );
			if( property != null ) {
				if( properties.containsKey( property.getName() ) ) {
					Property current = properties.get( property.getName() );
					if( current.isExplicit() && property.isExplicit() ) {
						throw new InvalidPropertyException(
								property.getName(),
								property.getDeclaringClass(),
								"Property %s (in %s) is explicitly defined with @Property as both field and method properties; Moo expects no more than one annotation per property name per class." );
					} else if( !current.isExplicit() && property.isExplicit() ) {
						properties.put( property.getName(), property );
					}
				} else {
					properties.put( property.getName(), property );
				}
			}
		}
		return new HashSet<Property>( properties.values() );
	}

	private <V> boolean updateProperty(Object source, T destination,
			TranslationSource translationSource, Property property,
			Map<String, Object> variables) {
		try {
			Object value = getValue( source,
					property.getTranslationExpression(), variables );
			updateOrReplaceProperty( destination, value, property,
					translationSource );
			return true;
		} catch( PropertyAccessException exception ) {
			if( property.isSourceRequired( configuration.isSourcePropertyRequired() ) ) {
				throw new MissingSourcePropertyException(
						property.getTranslationExpression(),
						source.getClass(),
						exception );
			}
			return false;
		}
	}

	@SuppressWarnings("unchecked")
	private void updateOrReplaceProperty(T destination, Object value,
			Property property, TranslationSource translationSource) {
		Object destinationValue = property.canGetValue() ? property.getValue( destination )
				: null;
		if( property.shouldUpdate() && value != null
				&& destinationValue != null ) {
			if( property.isTypeOrSubtype( Collection.class ) ) {
				updateCollection( value, (Collection<Object>) destinationValue,
						(CollectionProperty) property, translationSource );
			} else if( property.isTypeOrSubtype( Map.class ) ) {
				updateMap( value, (Map<Object, Object>) destinationValue,
						(CollectionProperty) property, translationSource );
			} else {
				translationSource.update( value, destinationValue );
			}
		}
		else {
			value = transform( value, property, translationSource );
			property.setValue( destination, value );
		}
	}

	private void updateMap(Object source, Map<Object, Object> destinationMap,
			CollectionProperty property,
			TranslationSource translationSource) {
		configuration.getCollectionTranslator().updateMap( source,
				destinationMap, translationSource,
				property );

	}

	private void updateCollection(Object source,
			Collection<Object> destinationCollection,
			CollectionProperty property,
			TranslationSource translationSource) {
		configuration.getCollectionTranslator().updateCollection( source,
				destinationCollection, translationSource,
				property );
	}
}
