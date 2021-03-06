/*
 * Copyright 2012-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.context.properties.bind;

import java.beans.PropertyEditor;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.PropertyEditorRegistry;
import org.springframework.beans.SimpleTypeConverter;
import org.springframework.beans.propertyeditors.FileEditor;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.util.Assert;

/**
 * Utility to handle any conversion needed during binding. This class is not thread-safe
 * and so a new instance is created for each top-level bind.
 *
 * @author Phillip Webb
 * @author Andy Wilkinson
 */
class BindConverter {

	private static final Set<Class<?>> EXCLUDED_EDITORS;
	static {
		Set<Class<?>> excluded = new HashSet<>();
		excluded.add(FileEditor.class); // gh-12163
		EXCLUDED_EDITORS = Collections.unmodifiableSet(excluded);
	}

	private final ConversionService conversionService;

	BindConverter(ConversionService conversionService,
			Consumer<PropertyEditorRegistry> propertyEditorInitializer) {
		Assert.notNull(conversionService, "ConversionService must not be null");
		this.conversionService = new CompositeConversionService(
				new TypeConverterConversionService(propertyEditorInitializer),
				conversionService);
	}

	public boolean canConvert(Object value, ResolvableType type,
			Annotation... annotations) {
		TypeDescriptor sourceType = TypeDescriptor.forObject(value);
		TypeDescriptor targetType = new ResolvableTypeDescriptor(type, annotations);
		return this.conversionService.canConvert(sourceType, targetType);
	}

	public <T> T convert(Object result, Bindable<T> target) {
		return convert(result, target.getType(), target.getAnnotations());
	}

	@SuppressWarnings("unchecked")
	public <T> T convert(Object value, ResolvableType type, Annotation... annotations) {
		if (value == null) {
			return null;
		}
		TypeDescriptor sourceType = TypeDescriptor.forObject(value);
		TypeDescriptor targetType = new ResolvableTypeDescriptor(type, annotations);
		return (T) this.conversionService.convert(value, sourceType, targetType);
	}

	/**
	 * A {@link TypeDescriptor} backed by a {@link ResolvableType}.
	 */
	private static class ResolvableTypeDescriptor extends TypeDescriptor {

		ResolvableTypeDescriptor(ResolvableType resolvableType,
				Annotation[] annotations) {
			super(resolvableType, null, annotations);
		}

	}

	/**
	 * A {@link ConversionService} implementation that delegates to a
	 * {@link SimpleTypeConverter}. Allows {@link PropertyEditor} based conversion for
	 * simple types, arrays and collections.
	 */
	private static class TypeConverterConversionService extends GenericConversionService {

		private SimpleTypeConverter typeConverter;

		TypeConverterConversionService(Consumer<PropertyEditorRegistry> initializer) {
			this.typeConverter = new SimpleTypeConverter();
			if (initializer != null) {
				initializer.accept(this.typeConverter);
			}
			addConverter(new TypeConverterConverter(this.typeConverter));
			ApplicationConversionService.addDelimitedStringConverters(this);
		}

		@Override
		public boolean canConvert(TypeDescriptor sourceType, TypeDescriptor targetType) {
			// Prefer conversion service to handle things like String to char[].
			if (targetType.isArray()
					&& targetType.getElementTypeDescriptor().isPrimitive()) {
				return false;
			}
			return super.canConvert(sourceType, targetType);
		}

	}

	/**
	 * {@link ConditionalGenericConverter} that delegates to {@link SimpleTypeConverter}.
	 */
	private static class TypeConverterConverter implements ConditionalGenericConverter {

		private SimpleTypeConverter typeConverter;

		TypeConverterConverter(SimpleTypeConverter typeConverter) {
			this.typeConverter = typeConverter;
		}

		@Override
		public Set<ConvertiblePair> getConvertibleTypes() {
			return Collections.singleton(new ConvertiblePair(String.class, Object.class));
		}

		@Override
		public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
			return getPropertyEditor(targetType.getType()) != null;
		}

		@Override
		public Object convert(Object source, TypeDescriptor sourceType,
				TypeDescriptor targetType) {
			return this.typeConverter.convertIfNecessary(source, targetType.getType());
		}

		private PropertyEditor getPropertyEditor(Class<?> type) {
			if (type == null || type == Object.class
					|| Collection.class.isAssignableFrom(type)
					|| Map.class.isAssignableFrom(type)) {
				return null;
			}
			PropertyEditor editor = this.typeConverter.getDefaultEditor(type);
			if (editor == null) {
				editor = this.typeConverter.findCustomEditor(type, null);
			}
			if (editor == null && String.class != type) {
				editor = BeanUtils.findEditorByConvention(type);
			}
			if (editor == null || EXCLUDED_EDITORS.contains(editor.getClass())) {
				return null;
			}
			return editor;
		}

	}

	private static final class CompositeConversionService implements ConversionService {

		private final List<ConversionService> delegates;

		private CompositeConversionService(
				TypeConverterConversionService typeConverterConversionService,
				ConversionService conversionService) {
			List<ConversionService> delegates = new ArrayList<ConversionService>();
			delegates.add(typeConverterConversionService);
			delegates.add(conversionService);
			if (!(conversionService instanceof ApplicationConversionService)) {
				delegates.add(ApplicationConversionService.getSharedInstance());
			}
			this.delegates = delegates;
		}

		@Override
		public boolean canConvert(Class<?> sourceType, Class<?> targetType) {
			return canConvert((delegate) -> delegate.canConvert(sourceType, targetType));
		}

		@Override
		public boolean canConvert(TypeDescriptor sourceType, TypeDescriptor targetType) {
			return canConvert((delegate) -> delegate.canConvert(sourceType, targetType));
		}

		private boolean canConvert(Predicate<ConversionService> canConvert) {
			for (ConversionService delegate : this.delegates) {
				if (canConvert.test(delegate)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public <T> T convert(Object source, Class<T> targetType) {
			Class<?> sourceType = source.getClass();
			return convert((delegate) -> delegate.canConvert(sourceType, targetType),
					(delegate) -> delegate.convert(source, targetType));
		}

		@Override
		public Object convert(Object source, TypeDescriptor sourceType,
				TypeDescriptor targetType) {
			return convert((delegate) -> delegate.canConvert(sourceType, targetType),
					(delegate) -> delegate.convert(source, sourceType, targetType));
		}

		public <T> T convert(Predicate<ConversionService> canConvert,
				Function<ConversionService, T> convert) {
			for (int i = 0; i < this.delegates.size() - 1; i++) {
				ConversionService delegate = this.delegates.get(i);
				if (canConvert.test(delegate)) {
					return convert.apply(delegate);
				}
			}
			return convert.apply(this.delegates.get(this.delegates.size() - 1));
		}

	}

}
