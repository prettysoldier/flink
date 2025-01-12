/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.typeutils;

import org.apache.flink.api.common.typeinfo.SqlTimeTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.CompositeType;
import org.apache.flink.api.java.typeutils.GenericTypeInfo;
import org.apache.flink.api.java.typeutils.PojoTypeInfo;
import org.apache.flink.api.java.typeutils.TupleTypeInfoBase;
import org.apache.flink.table.api.TableException;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.api.Types;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.expressions.ApiExpressionDefaultVisitor;
import org.apache.flink.table.expressions.CallExpression;
import org.apache.flink.table.expressions.Expression;
import org.apache.flink.table.expressions.ExpressionUtils;
import org.apache.flink.table.expressions.UnresolvedReferenceExpression;
import org.apache.flink.table.functions.BuiltInFunctionDefinitions;
import org.apache.flink.table.types.AtomicDataType;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.LogicalTypeRoot;
import org.apache.flink.table.types.logical.TimestampKind;
import org.apache.flink.table.types.logical.TimestampType;
import org.apache.flink.types.Row;

import javax.annotation.Nullable;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.String.format;
import static org.apache.flink.table.types.logical.utils.LogicalTypeChecks.hasRoot;
import static org.apache.flink.table.types.logical.utils.LogicalTypeChecks.isProctimeAttribute;
import static org.apache.flink.table.types.logical.utils.LogicalTypeChecks.isRowtimeAttribute;
import static org.apache.flink.table.types.utils.TypeConversions.fromLegacyInfoToDataType;

/**
 * Utility methods for extracting names and indices of fields from different {@link TypeInformation}s.
 */
public class FieldInfoUtils {

	private static final String ATOMIC_FIELD_NAME = "f0";

	/**
	 * Describes fields' names, indices and {@link DataType}s extracted from a {@link TypeInformation} and possibly
	 * transformed via {@link Expression} application. It is in fact a mapping between {@link TypeInformation} of an
	 * input and {@link TableSchema} of a {@link org.apache.flink.table.api.Table} that can be created out of it.
	 *
	 * @see FieldInfoUtils#getFieldsInfo(TypeInformation)
	 * @see FieldInfoUtils#getFieldsInfo(TypeInformation, Expression[])
	 */
	public static class TypeInfoSchema {
		private final String[] fieldNames;
		private final int[] indices;
		private final DataType[] fieldTypes;
		private final boolean isRowtimeDefined;

		TypeInfoSchema(
				String[] fieldNames,
				int[] indices,
				DataType[] fieldTypes,
				boolean isRowtimeDefined) {
			validateEqualLength(fieldNames, indices, fieldTypes);
			validateNamesUniqueness(fieldNames);

			this.isRowtimeDefined = isRowtimeDefined;
			this.fieldNames = fieldNames;
			this.indices = indices;
			this.fieldTypes = fieldTypes;
		}

		private void validateEqualLength(String[] fieldNames, int[] indices, DataType[] fieldTypes) {
			if (fieldNames.length != indices.length || indices.length != fieldTypes.length) {
				throw new TableException(String.format("Mismatched number of indices, names and types:\n" +
					"Names: %s\n" +
					"Indices: %s\n" +
					"Types: %s", Arrays.toString(fieldNames), Arrays.toString(indices), Arrays.toString(fieldTypes)));
			}
		}

		private void validateNamesUniqueness(String[] fieldNames) {
			// check uniqueness of field names
			Set<String> duplicatedNames = findDuplicates(fieldNames);
			if (duplicatedNames.size() != 0) {

				throw new ValidationException(String.format(
					"Field names must be unique.\n" +
						"List of duplicate fields: [%s].\n" +
						"List of all fields: [%s].",
					String.join(", ", duplicatedNames),
					String.join(", ", fieldNames)));
			}
		}

		public String[] getFieldNames() {
			return fieldNames;
		}

		public int[] getIndices() {
			return indices;
		}

		public DataType[] getFieldTypes() {
			return fieldTypes;
		}

		public boolean isRowtimeDefined() {
			return isRowtimeDefined;
		}

		public TableSchema toTableSchema() {
			return TableSchema.builder().fields(fieldNames, fieldTypes).build();
		}
	}

	/**
	 * Reference input fields by name:
	 * All fields in the schema definition are referenced by name
	 * (and possibly renamed using an alias (as). In this mode, fields can be reordered and
	 * projected out. Moreover, we can define proctime and rowtime attributes at arbitrary
	 * positions using arbitrary names (except those that exist in the result schema). This mode
	 * can be used for any input type, including POJOs.
	 *
	 * <p>Reference input fields by position:
	 * In this mode, fields are simply renamed. Event-time attributes can
	 * replace the field on their position in the input data (if it is of correct type) or be
	 * appended at the end. Proctime attributes must be appended at the end. This mode can only be
	 * used if the input type has a defined field order (tuple, case class, Row) and no of fields
	 * references a field of the input type.
	 */
	public static boolean isReferenceByPosition(CompositeType<?> ct, Expression[] fields) {
		if (!(ct instanceof TupleTypeInfoBase)) {
			return false;
		}

		List<String> inputNames = Arrays.asList(ct.getFieldNames());

		// Use the by-position mode if no of the fields exists in the input.
		// This prevents confusing cases like ('f2, 'f0, 'myName) for a Tuple3 where fields are renamed
		// by position but the user might assume reordering instead of renaming.
		return Arrays.stream(fields).allMatch(f -> {
			if (f instanceof UnresolvedReferenceExpression) {
				return !inputNames.contains(((UnresolvedReferenceExpression) f).getName());
			}

			return true;
		});
	}

	/**
	 * Returns a {@link TypeInfoSchema} for a given {@link TypeInformation}.
	 *
	 * @param inputType The TypeInformation to extract the mapping from.
	 * @param <A> The type of the TypeInformation.
	 * @return A description of the input that enables creation of a {@link TableSchema}.
	 * @see TypeInfoSchema
	 */
	public static <A> TypeInfoSchema getFieldsInfo(TypeInformation<A> inputType) {

		if (inputType instanceof GenericTypeInfo && inputType.getTypeClass() == Row.class) {
			throw new ValidationException(
				"An input of GenericTypeInfo<Row> cannot be converted to Table. " +
					"Please specify the type of the input with a RowTypeInfo.");
		} else {
			return new TypeInfoSchema(
				getFieldNames(inputType),
				getFieldIndices(inputType),
				fromLegacyInfoToDataType(getFieldTypes(inputType)),
				false);
		}
	}

	/**
	 * Returns a {@link TypeInfoSchema} for a given {@link TypeInformation}.
	 * It gives control of the process of mapping {@link TypeInformation} to {@link TableSchema}
	 * (via {@link TypeInfoSchema}).
	 *
	 * <p>Possible operations via the expressions include:
	 * <ul>
	 *     <li>specifying rowtime & proctime attributes via .proctime, .rowtime
	 *          <ul>
	 *              <li>There can be only a single rowtime and/or a single proctime attribute</li>
	 *              <li>Proctime attribute can only be appended to the end of the expression list</li>
	 *              <li>Rowtime attribute can replace an input field if the input field has a compatible type.
	 *              See {@link TimestampType}.</li>
	 *          </ul>
	 *     </li>
	 *     <li>renaming fields by position (this cannot be mixed with referencing by name)</li>
	 *     <li>renaming & projecting fields by name (this cannot be mixed with referencing by position)</li>
	 * </ul>
	 *
	 * @param inputType The TypeInformation to extract the mapping from.
	 * @param expressions Expressions to apply while extracting the mapping.
	 * @param <A> The type of the TypeInformation.
	 * @return A description of the input that enables creation of a {@link TableSchema}.
	 * @see TypeInfoSchema
	 */
	public static <A> TypeInfoSchema getFieldsInfo(TypeInformation<A> inputType, Expression[] expressions) {
		validateInputTypeInfo(inputType);

		final List<FieldInfo> fieldInfos = extractFieldInformation(inputType, expressions);

		validateNoStarReference(fieldInfos);
		boolean isRowtimeAttribute = checkIfRowtimeAttribute(fieldInfos);
		validateAtMostOneProctimeAttribute(fieldInfos);

		String[] fieldNames = fieldInfos.stream().map(FieldInfo::getFieldName).toArray(String[]::new);
		int[] fieldIndices = fieldInfos.stream().mapToInt(FieldInfo::getIndex).toArray();
		DataType[] dataTypes = fieldInfos.stream().map(FieldInfo::getType).toArray(DataType[]::new);

		return new TypeInfoSchema(fieldNames, fieldIndices, dataTypes, isRowtimeAttribute);
	}

	private static void validateNoStarReference(List<FieldInfo> fieldInfos) {
		if (fieldInfos.stream().anyMatch(info -> info.getFieldName().equals("*"))) {
			throw new ValidationException("Field name can not be '*'.");
		}
	}

	private static <A> List<FieldInfo> extractFieldInformation(
		TypeInformation<A> inputType,
		Expression[] exprs) {
		final List<FieldInfo> fieldInfos;
		if (inputType instanceof GenericTypeInfo && inputType.getTypeClass() == Row.class) {
			throw new ValidationException(
				"An input of GenericTypeInfo<Row> cannot be converted to Table. " +
					"Please specify the type of the input with a RowTypeInfo.");
		} else if (inputType instanceof TupleTypeInfoBase) {
			fieldInfos = extractFieldInfosFromTupleType((TupleTypeInfoBase<?>) inputType, exprs);
		} else if (inputType instanceof PojoTypeInfo) {
			fieldInfos = extractFieldInfosByNameReference((CompositeType<?>) inputType, exprs);
		} else {
			fieldInfos = extractFieldInfoFromAtomicType(inputType, exprs);
		}
		return fieldInfos;
	}

	private static void validateAtMostOneProctimeAttribute(List<FieldInfo> fieldInfos) {
		List<FieldInfo> proctimeAttributes = fieldInfos.stream()
			.filter(FieldInfoUtils::isProctimeField)
			.collect(Collectors.toList());

		if (proctimeAttributes.size() > 1) {
			throw new ValidationException(
				"The proctime attribute can only be defined once in a table schema. Duplicated proctime attributes: " +
					proctimeAttributes);
		}
	}

	private static boolean checkIfRowtimeAttribute(List<FieldInfo> fieldInfos) {
		List<FieldInfo> rowtimeAttributes = fieldInfos.stream()
			.filter(FieldInfoUtils::isRowtimeField)
			.collect(Collectors.toList());

		if (rowtimeAttributes.size() > 1) {
			throw new ValidationException(
				"The rowtime attribute can only be defined once in a table schema. Duplicated rowtime attributes: " +
					rowtimeAttributes);
		}
		return rowtimeAttributes.size() > 0;
	}

	/**
	 * Returns field names for a given {@link TypeInformation}.
	 *
	 * @param inputType The TypeInformation extract the field names.
	 * @param <A> The type of the TypeInformation.
	 * @return An array holding the field names
	 */
	public static <A> String[] getFieldNames(TypeInformation<A> inputType) {
		return getFieldNames(inputType, Collections.emptyList());
	}

	/**
	 * Returns field names for a given {@link TypeInformation}. If the input {@link TypeInformation}
	 * is not a composite type, the result field name should not exist in the existingNames.
	 *
	 * @param inputType The TypeInformation extract the field names.
	 * @param existingNames The existing field names for non-composite types that can not be used.
	 * @param <A> The type of the TypeInformation.
	 * @return An array holding the field names
	 */
	public static <A> String[] getFieldNames(TypeInformation<A> inputType, List<String> existingNames) {
		validateInputTypeInfo(inputType);

		String[] fieldNames;
		if (inputType instanceof CompositeType) {
			fieldNames = ((CompositeType<A>) inputType).getFieldNames();
		} else {
			int i = 0;
			String fieldName = ATOMIC_FIELD_NAME;
			while ((null != existingNames) && existingNames.contains(fieldName)) {
				fieldName = ATOMIC_FIELD_NAME + "_" + i++;
			}
			fieldNames = new String[]{fieldName};
		}

		if (Arrays.asList(fieldNames).contains("*")) {
			throw new TableException("Field name can not be '*'.");
		}

		return fieldNames;
	}

	/**
	 * Validate if class represented by the typeInfo is static and globally accessible.
	 *
	 * @param typeInfo type to check
	 * @throws ValidationException if type does not meet these criteria
	 */
	public static <A> void validateInputTypeInfo(TypeInformation<A> typeInfo) {
		Class<A> clazz = typeInfo.getTypeClass();
		if ((clazz.isMemberClass() && !Modifier.isStatic(clazz.getModifiers())) ||
			!Modifier.isPublic(clazz.getModifiers()) ||
			clazz.getCanonicalName() == null) {
			throw new ValidationException(format(
				"Class '%s' described in type information '%s' must be " +
				"static and globally accessible.", clazz, typeInfo));
		}
	}

	/**
	 * Returns field indexes for a given {@link TypeInformation}.
	 *
	 * @param inputType The TypeInformation extract the field positions from.
	 * @return An array holding the field positions
	 */
	public static int[] getFieldIndices(TypeInformation<?> inputType) {
		return IntStream.range(0, getFieldNames(inputType).length).toArray();
	}

	/**
	 * Returns field types for a given {@link TypeInformation}.
	 *
	 * @param inputType The TypeInformation to extract field types from.
	 * @return An array holding the field types.
	 */
	public static TypeInformation<?>[] getFieldTypes(TypeInformation<?> inputType) {
		validateInputTypeInfo(inputType);

		final TypeInformation<?>[] fieldTypes;
		if (inputType instanceof CompositeType) {
			int arity = inputType.getArity();
			CompositeType ct = (CompositeType<?>) inputType;
			fieldTypes = IntStream.range(0, arity).mapToObj(ct::getTypeAt).toArray(TypeInformation[]::new);
		} else {
			fieldTypes = new TypeInformation[]{inputType};
		}

		return fieldTypes;
	}

	/* Utility methods */

	private static List<FieldInfo> extractFieldInfoFromAtomicType(TypeInformation<?> atomicType, Expression[] exprs) {
		List<FieldInfo> fields = new ArrayList<>(exprs.length);
		boolean alreadyReferenced = false;
		for (int i = 0; i < exprs.length; i++) {
			Expression expr = exprs[i];
			if (expr instanceof UnresolvedReferenceExpression) {
				if (alreadyReferenced) {
					throw new ValidationException("Too many fields referenced from an atomic type.");
				}

				alreadyReferenced = true;
				String name = ((UnresolvedReferenceExpression) expr).getName();
				fields.add(new FieldInfo(name, i, fromLegacyInfoToDataType(atomicType)));
			} else if (isRowTimeExpression(expr)) {
				UnresolvedReferenceExpression reference = getChildAsReference(expr);
				fields.add(createTimeAttributeField(reference, TimestampKind.ROWTIME, null));
			} else if (isProcTimeExpression(expr)) {
				UnresolvedReferenceExpression reference = getChildAsReference(expr);
				fields.add(createTimeAttributeField(reference, TimestampKind.PROCTIME, null));
			} else {
				throw new ValidationException("Field reference expression expected.");
			}
		}
		return fields;
	}

	private static List<FieldInfo> extractFieldInfosFromTupleType(TupleTypeInfoBase<?> inputType, Expression[] exprs) {
		boolean isRefByPos = isReferenceByPosition(inputType, exprs);

		if (isRefByPos) {
			return IntStream.range(0, exprs.length)
				.mapToObj(idx -> exprs[idx].accept(new IndexedExprToFieldInfo(inputType, idx)))
				.collect(Collectors.toList());
		} else {
			return extractFieldInfosByNameReference(inputType, exprs);
		}
	}

	private static List<FieldInfo> extractFieldInfosByNameReference(CompositeType<?> inputType, Expression[] exprs) {
		ExprToFieldInfo exprToFieldInfo = new ExprToFieldInfo(inputType);
		return Arrays.stream(exprs)
			.map(expr -> expr.accept(exprToFieldInfo))
			.collect(Collectors.toList());
	}

	private static class FieldInfo {
		private final String fieldName;
		private final int index;
		private final DataType type;

		FieldInfo(String fieldName, int index, DataType type) {
			this.fieldName = fieldName;
			this.index = index;
			this.type = type;
		}

		public String getFieldName() {
			return fieldName;
		}

		public int getIndex() {
			return index;
		}

		public DataType getType() {
			return type;
		}
	}

	private static class IndexedExprToFieldInfo extends ApiExpressionDefaultVisitor<FieldInfo> {

		private final CompositeType<?> inputType;
		private final int index;

		private IndexedExprToFieldInfo(CompositeType<?> inputType, int index) {
			this.inputType = inputType;
			this.index = index;
		}

		@Override
		public FieldInfo visitUnresolvedReference(UnresolvedReferenceExpression unresolvedReference) {
			String fieldName = unresolvedReference.getName();
			return new FieldInfo(fieldName, index, fromLegacyInfoToDataType(getTypeAt(unresolvedReference)));
		}

		@Override
		public FieldInfo visitCall(CallExpression call) {
			if (call.getFunctionDefinition() == BuiltInFunctionDefinitions.AS) {
				return visitAlias(call);
			} else if (isRowTimeExpression(call)) {
				validateRowtimeReplacesCompatibleType(call);
				return createTimeAttributeField(getChildAsReference(call), TimestampKind.ROWTIME, null);
			} else if (isProcTimeExpression(call)) {
				validateProcTimeAttributeAppended(call);
				return createTimeAttributeField(getChildAsReference(call), TimestampKind.PROCTIME, null);
			}

			return defaultMethod(call);
		}

		private FieldInfo visitAlias(CallExpression call) {
			List<Expression> children = call.getChildren();
			String newName = extractAlias(children.get(1));

			Expression child = children.get(0);
			if (isProcTimeExpression(child)) {
				validateProcTimeAttributeAppended(call);
				return createTimeAttributeField(getChildAsReference(child), TimestampKind.PROCTIME, newName);
			} else {
				throw new ValidationException(
					format("Alias '%s' is not allowed if other fields are referenced by position.", newName));
			}
		}

		private void validateRowtimeReplacesCompatibleType(CallExpression call) {
			if (index < inputType.getArity()) {
				checkRowtimeType(getTypeAt(call));
			}
		}

		private void validateProcTimeAttributeAppended(CallExpression call) {
			if (index < inputType.getArity()) {
				throw new ValidationException(String.format("The proctime attribute can only be appended to the" +
					" table schema and not replace an existing field. Please move '%s' to the end of the" +
					" schema.", call));
			}
		}

		private TypeInformation<Object> getTypeAt(Expression expr) {
			if (index >= inputType.getArity()) {
				throw new ValidationException(String.format(
					"Number of expressions does not match number of input fields.\n" +
						"Available fields: %s\n" +
						"Could not map: %s",
					Arrays.toString(inputType.getFieldNames()),
					expr));
			}
			return inputType.getTypeAt(index);
		}

		@Override
		protected FieldInfo defaultMethod(Expression expression) {
			throw new ValidationException("Field reference expression or alias on field expression expected.");
		}
	}

	private static class ExprToFieldInfo extends ApiExpressionDefaultVisitor<FieldInfo> {

		private final CompositeType ct;

		private ExprToFieldInfo(CompositeType ct) {
			this.ct = ct;
		}

		private ValidationException fieldNotFound(String name) {
			return new ValidationException(format(
				"%s is not a field of type %s. Expected: %s}",
				name,
				ct,
				String.join(", ", ct.getFieldNames())));
		}

		@Override
		public FieldInfo visitUnresolvedReference(UnresolvedReferenceExpression unresolvedReference) {
			return createFieldInfo(unresolvedReference, null);
		}

		@Override
		public FieldInfo visitCall(CallExpression call) {
			if (call.getFunctionDefinition() == BuiltInFunctionDefinitions.AS) {
				return visitAlias(call);
			} else if (isRowTimeExpression(call)) {
				return createRowtimeFieldInfo(call, null);
			} else if (isProcTimeExpression(call)) {
				return createProctimeFieldInfo(call, null);
			}

			return defaultMethod(call);
		}

		private FieldInfo visitAlias(CallExpression call) {
			List<Expression> children = call.getChildren();
			String newName = extractAlias(children.get(1));

			Expression child = children.get(0);
			if (child instanceof UnresolvedReferenceExpression) {
				return createFieldInfo((UnresolvedReferenceExpression) child, newName);
			} else if (isRowTimeExpression(child)) {
				return createRowtimeFieldInfo(child, newName);
			} else if (isProcTimeExpression(child)) {
				return createProctimeFieldInfo(child, newName);
			} else {
				return defaultMethod(call);
			}
		}

		private FieldInfo createFieldInfo(UnresolvedReferenceExpression unresolvedReference, @Nullable String alias) {
			String fieldName = unresolvedReference.getName();
			return referenceByName(fieldName, ct)
				.map(idx -> new FieldInfo(
					alias != null ? alias : fieldName,
					idx,
					fromLegacyInfoToDataType(ct.getTypeAt(idx))))
				.orElseThrow(() -> fieldNotFound(fieldName));
		}

		private FieldInfo createProctimeFieldInfo(Expression expression, @Nullable String alias) {
			UnresolvedReferenceExpression reference = getChildAsReference(expression);
			String originalName = reference.getName();
			validateProctimeDoesNotReplaceField(originalName);

			return createTimeAttributeField(reference, TimestampKind.PROCTIME, alias);
		}

		private void validateProctimeDoesNotReplaceField(String originalName) {
			if (referenceByName(originalName, ct).isPresent()) {
				throw new ValidationException(String.format(
					"The proctime attribute '%s' must not replace an existing field.",
					originalName));
			}
		}

		private FieldInfo createRowtimeFieldInfo(Expression expression, @Nullable String alias) {
			UnresolvedReferenceExpression reference = getChildAsReference(expression);
			String originalName = reference.getName();
			verifyReferencesValidField(originalName, alias);

			return createTimeAttributeField(reference, TimestampKind.ROWTIME, alias);
		}

		private void verifyReferencesValidField(String origName, @Nullable String alias) {
			Optional<Integer> refId = referenceByName(origName, ct);
			if (refId.isPresent()) {
				checkRowtimeType(ct.getTypeAt(refId.get()));
			} else if (alias != null) {
				throw new ValidationException(String.format("Alias '%s' must reference an existing field.", alias));
			}
		}

		@Override
		protected FieldInfo defaultMethod(Expression expression) {
			throw new ValidationException("Field reference expression or alias on field expression expected.");
		}
	}

	private static String extractAlias(Expression aliasExpr) {
		return ExpressionUtils.extractValue(aliasExpr, String.class)
			.orElseThrow(() -> new TableException("Alias expects string literal as new name. Got: " + aliasExpr));
	}

	private static void checkRowtimeType(TypeInformation<?> type) {
		if (!(type.equals(Types.LONG()) || type instanceof SqlTimeTypeInfo)) {
			throw new ValidationException(
				"The rowtime attribute can only replace a field with a valid time type, " +
					"such as Timestamp or Long. But was: " + type);
		}
	}

	private static boolean isRowtimeField(FieldInfo field) {
		DataType type = field.getType();
		return hasRoot(type.getLogicalType(), LogicalTypeRoot.TIMESTAMP_WITHOUT_TIME_ZONE) &&
			isRowtimeAttribute(type.getLogicalType());
	}

	private static boolean isProctimeField(FieldInfo field) {
		DataType type = field.getType();
		return hasRoot(type.getLogicalType(), LogicalTypeRoot.TIMESTAMP_WITHOUT_TIME_ZONE) &&
			isProctimeAttribute(type.getLogicalType());
	}

	private static boolean isRowTimeExpression(Expression origExpr) {
		return origExpr instanceof CallExpression &&
			((CallExpression) origExpr).getFunctionDefinition() == BuiltInFunctionDefinitions.ROWTIME;
	}

	private static boolean isProcTimeExpression(Expression origExpr) {
		return origExpr instanceof CallExpression &&
			((CallExpression) origExpr).getFunctionDefinition() == BuiltInFunctionDefinitions.PROCTIME;
	}

	private static Optional<Integer> referenceByName(String name, CompositeType<?> ct) {
		int inputIdx = ct.getFieldIndex(name);
		if (inputIdx < 0) {
			return Optional.empty();
		} else {
			return Optional.of(inputIdx);
		}
	}

	private static <T> Set<T> findDuplicates(T[] array) {
		Set<T> duplicates = new HashSet<>();
		Set<T> seenElements = new HashSet<>();

		for (T t : array) {
			if (seenElements.contains(t)) {
				duplicates.add(t);
			} else {
				seenElements.add(t);
			}
		}

		return duplicates;
	}

	private static FieldInfo createTimeAttributeField(
			UnresolvedReferenceExpression reference,
			TimestampKind kind,
			@Nullable String alias) {
		final int idx;
		if (kind == TimestampKind.PROCTIME) {
			idx = TimeIndicatorTypeInfo.PROCTIME_STREAM_MARKER;
		} else {
			idx = TimeIndicatorTypeInfo.ROWTIME_STREAM_MARKER;
		}

		String originalName = reference.getName();
		return new FieldInfo(
			alias != null ? alias : originalName,
			idx,
			createTimeIndicatorType(kind));
	}

	private static UnresolvedReferenceExpression getChildAsReference(Expression expression) {
		Expression child = expression.getChildren().get(0);
		if (child instanceof UnresolvedReferenceExpression) {
			return (UnresolvedReferenceExpression) child;
		}

		throw new ValidationException("Field reference expression expected.");
	}

	private static DataType createTimeIndicatorType(TimestampKind kind) {
		return new AtomicDataType(new TimestampType(true, kind, 3))
			.bridgedTo(java.sql.Timestamp.class);
	}

	private FieldInfoUtils() {
	}
}
