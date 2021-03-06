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
package org.apache.beam.sdk.schemas;

import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;
import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkState;

import com.google.auto.value.AutoOneOf;
import com.google.auto.value.AutoValue;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;
import org.apache.beam.sdk.schemas.FieldAccessDescriptor.FieldDescriptor.ListQualifier;
import org.apache.beam.sdk.schemas.FieldAccessDescriptor.FieldDescriptor.MapQualifier;
import org.apache.beam.sdk.schemas.FieldAccessDescriptor.FieldDescriptor.Qualifier;
import org.apache.beam.sdk.schemas.Schema.Field;
import org.apache.beam.sdk.schemas.Schema.FieldType;
import org.apache.beam.sdk.schemas.Schema.TypeName;
import org.apache.beam.sdk.schemas.parser.FieldAccessDescriptorParser;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ArrayListMultimap;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableList;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableMap;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.Iterables;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.Lists;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.Maps;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.Multimap;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.Sets;

/**
 * Used inside of a {@link org.apache.beam.sdk.transforms.DoFn} to describe which fields in a schema
 * type need to be accessed for processing.
 *
 * <p>This class always puts the selected fields in a deterministic order.
 */
@Experimental(Kind.SCHEMAS)
@AutoValue
public abstract class FieldAccessDescriptor implements Serializable {
  /** Description of a single field. */
  @AutoValue
  public abstract static class FieldDescriptor implements Serializable {
    /** Qualifier for a list selector. */
    public enum ListQualifier {
      ALL // Select all elements of the list.
    }

    /** Qualifier for a map selector. */
    public enum MapQualifier {
      ALL // Select all elements of the map.
    }

    /** OneOf union for a collection selector. */
    @AutoOneOf(Qualifier.Kind.class)
    public abstract static class Qualifier implements Serializable {
      /** The kind of qualifier. */
      public enum Kind {
        LIST,
        MAP
      };

      public abstract Kind getKind();

      public abstract ListQualifier getList();

      public abstract MapQualifier getMap();

      public static Qualifier of(ListQualifier qualifier) {
        return AutoOneOf_FieldAccessDescriptor_FieldDescriptor_Qualifier.list(qualifier);
      }

      public static Qualifier of(MapQualifier qualifier) {
        return AutoOneOf_FieldAccessDescriptor_FieldDescriptor_Qualifier.map(qualifier);
      }
    }

    @Nullable
    public abstract String getFieldName();

    @Nullable
    public abstract Integer getFieldId();

    public abstract List<Qualifier> getQualifiers();

    public static Builder builder() {
      return new AutoValue_FieldAccessDescriptor_FieldDescriptor.Builder()
          .setQualifiers(Collections.emptyList());
    }

    /** Builder class. */
    @AutoValue.Builder
    public abstract static class Builder {
      public abstract Builder setFieldName(@Nullable String name);

      public abstract Builder setFieldId(@Nullable Integer id);

      public abstract Builder setQualifiers(List<Qualifier> qualifiers);

      public abstract FieldDescriptor build();
    }

    abstract Builder toBuilder();
  }

  @AutoValue.Builder
  abstract static class Builder {
    abstract Builder setAllFields(boolean allFields);

    abstract Builder setFieldsAccessed(List<FieldDescriptor> fieldsAccessed);

    abstract Builder setNestedFieldsAccessed(
        Map<FieldDescriptor, FieldAccessDescriptor> nestedFieldsAccessedById);

    abstract Builder setFieldInsertionOrder(boolean insertionOrder);

    abstract FieldAccessDescriptor build();
  }

  /** If true, all fields are being accessed. */
  public abstract boolean getAllFields();

  public abstract List<FieldDescriptor> getFieldsAccessed();

  public abstract Map<FieldDescriptor, FieldAccessDescriptor> getNestedFieldsAccessed();

  public abstract boolean getFieldInsertionOrder();

  abstract Builder toBuilder();

  static Builder builder() {
    return new AutoValue_FieldAccessDescriptor.Builder()
        .setAllFields(false)
        .setFieldInsertionOrder(false)
        .setFieldsAccessed(Collections.emptyList())
        .setNestedFieldsAccessed(Collections.emptyMap());
  }

  // Return a descriptor that accesses all fields in a row.
  public static FieldAccessDescriptor withAllFields() {
    return builder().setAllFields(true).build();
  }

  /**
   * Return a descriptor that access the specified fields.
   *
   * <p>By default, if the field is a nested row (or a container containing a row), all fields of
   * said rows are accessed. The syntax for a field name allows specifying nested fields and
   * wildcards, as specified in the file-level Javadoc. withNestedField can also be called to
   * specify recursive field access.
   */
  public static FieldAccessDescriptor withFieldNames(String... names) {
    return withFieldNames(Arrays.asList(names));
  }

  /**
   * Return a descriptor that access the specified fields.
   *
   * <p>By default, if the field is a nested row (or a container containing a row), all fields of
   * said rows are accessed. The syntax for a field name allows specifying nested fields and
   * wildcards, as specified in the file-level Javadoc. withNestedField can also be called to
   * specify recursive field access.
   */
  public static FieldAccessDescriptor withFieldNames(Iterable<String> fieldNames) {
    List<FieldAccessDescriptor> fields =
        StreamSupport.stream(fieldNames.spliterator(), false)
            .map(FieldAccessDescriptorParser::parse)
            .collect(Collectors.toList());
    return union(fields);
  }

  /**
   * Return a descriptor that access the specified fields.
   *
   * <p>By default, if the field is a nested row (or a container containing a row), all fields of
   * said rows are accessed. For finer-grained acccess to nested rows, call withNestedField and pass
   * in a recursive {@link FieldAccessDescriptor}.
   */
  public static FieldAccessDescriptor withFieldIds(Integer... ids) {
    return withFieldIds(Arrays.asList(ids));
  }

  /**
   * Return a descriptor that access the specified fields.
   *
   * <p>By default, if the field is a nested row (or a container containing a row), all fields of
   * said rows are accessed. For finer-grained acccess to nested rows, call withNestedField and pass
   * in a recursive {@link FieldAccessDescriptor}.
   */
  public static FieldAccessDescriptor withFieldIds(Iterable<Integer> ids) {
    List<FieldDescriptor> fields =
        StreamSupport.stream(ids.spliterator(), false)
            .map(n -> FieldDescriptor.builder().setFieldId(n).build())
            .collect(Collectors.toList());
    return withFields(fields);
  }

  /** Returns a {@link FieldAccessDescriptor} that accesses the specified fields. */
  public static FieldAccessDescriptor withFields(FieldDescriptor... fields) {
    return withFields(Arrays.asList(fields));
  }

  /** Returns a {@link FieldAccessDescriptor} that accesses the specified fields. */
  public static FieldAccessDescriptor withFields(Iterable<FieldDescriptor> fields) {
    return builder().setFieldsAccessed(Lists.newArrayList(fields)).build();
  }

  // Union a set of FieldAccessDescriptors. This function currently only supports descriptors with
  // containing named fields, not those containing ids.
  private static FieldAccessDescriptor union(
      Iterable<FieldAccessDescriptor> fieldAccessDescriptors) {
    Set<FieldDescriptor> fieldsAccessed = Sets.newLinkedHashSet();
    Multimap<FieldDescriptor, FieldAccessDescriptor> nestedFieldsAccessed =
        ArrayListMultimap.create();
    for (FieldAccessDescriptor fieldAccessDescriptor : fieldAccessDescriptors) {
      if (fieldAccessDescriptor.getAllFields()) {
        // If one of the descriptors is a wildcard, we can short circuit and return a wildcard.
        return FieldAccessDescriptor.withAllFields();
      }

      for (FieldDescriptor field : fieldAccessDescriptor.getFieldsAccessed()) {
        fieldsAccessed.add(field);
        // We're already reading the entire field, so no need to specify nested fields.
        nestedFieldsAccessed.removeAll(field);
      }
      for (Map.Entry<FieldDescriptor, FieldAccessDescriptor> nested :
          fieldAccessDescriptor.getNestedFieldsAccessed().entrySet()) {
        FieldDescriptor field = nested.getKey();
        nestedFieldsAccessed.put(field, nested.getValue());
      }
    }
    // Start off by unioning together the set of full fields we are accessing at this level.
    FieldAccessDescriptor fieldAccessDescriptor = FieldAccessDescriptor.withFields(fieldsAccessed);

    // Now, union all the nested fields.
    for (Map.Entry<FieldDescriptor, Collection<FieldAccessDescriptor>> entry :
        nestedFieldsAccessed.asMap().entrySet()) {
      if (fieldsAccessed.contains(entry.getKey())) {
        // We're already reading this entire field which includes all nested fields. Skip over
        // this field.
        continue;
      }
      // If there are multiple subdescriptors for this field (e.g. a.b, a.c, a.d), recursively
      // union them together and create a new nested field description.
      fieldAccessDescriptor =
          fieldAccessDescriptor.withNestedField(entry.getKey(), union(entry.getValue()));
    }
    return fieldAccessDescriptor;
  }

  /** Return an empty {@link FieldAccessDescriptor}. */
  public static FieldAccessDescriptor create() {
    return builder().build();
  }

  /**
   * Return a descriptor that access the specified nested field. The nested field must be of type
   * {@link Schema.TypeName#ROW}, and the fieldAccess argument specifies what fields of the nested
   * type will be accessed.
   */
  public FieldAccessDescriptor withNestedField(
      int nestedFieldId, FieldAccessDescriptor fieldAccess) {
    FieldDescriptor field = FieldDescriptor.builder().setFieldId(nestedFieldId).build();
    return withNestedField(field, fieldAccess);
  }

  /**
   * Return a descriptor that access the specified nested field. The nested field must be of type
   * {@link Schema.TypeName#ROW}, and the fieldAccess argument specifies what fields of the nested
   * type will be accessed.
   */
  public FieldAccessDescriptor withNestedField(
      String nestedFieldName, FieldAccessDescriptor fieldAccess) {
    FieldDescriptor field = FieldDescriptor.builder().setFieldName(nestedFieldName).build();
    return withNestedField(field, fieldAccess);
  }

  public FieldAccessDescriptor withNestedField(
      FieldDescriptor field, FieldAccessDescriptor fieldAccess) {
    Map<FieldDescriptor, FieldAccessDescriptor> newNestedFieldAccess =
        ImmutableMap.<FieldDescriptor, FieldAccessDescriptor>builder()
            .putAll(getNestedFieldsAccessed())
            .put(field, fieldAccess)
            .build();
    return toBuilder().setNestedFieldsAccessed(newNestedFieldAccess).build();
  }

  /**
   * By default, fields are sorted by name. If this is set, they will instead be sorted by insertion
   * order. All sorting happens in the {@link #resolve(Schema)} method.
   */
  public FieldAccessDescriptor withOrderByFieldInsertionOrder() {
    return toBuilder().setFieldInsertionOrder(true).build();
  }

  /**
   * Return the field ids accessed. Should not be called until after {@link #resolve} is called.
   * Iteration order is consistent with {@link #getFieldsAccessed}.
   */
  public List<Integer> fieldIdsAccessed() {
    return getFieldsAccessed().stream()
        .map(FieldDescriptor::getFieldId)
        .collect(Collectors.toList());
  }

  /**
   * Return the field names accessed. Should not be called until after {@link #resolve} is called.
   */
  public Set<String> fieldNamesAccessed() {
    return getFieldsAccessed().stream()
        .map(FieldDescriptor::getFieldName)
        .collect(Collectors.toSet());
  }

  /**
   * Return the nested fields keyed by field ids. Should not be called until after {@link #resolve}
   * is called.
   */
  public Map<Integer, FieldAccessDescriptor> nestedFieldsById() {
    return getNestedFieldsAccessed().entrySet().stream()
        .collect(Collectors.toMap(f -> f.getKey().getFieldId(), f -> f.getValue()));
  }

  /**
   * Return the nested fields keyed by field name. Should not be called until after {@link #resolve}
   * is called.
   */
  public Map<String, FieldAccessDescriptor> nestedFieldsByName() {
    return getNestedFieldsAccessed().entrySet().stream()
        .collect(Collectors.toMap(f -> f.getKey().getFieldName(), f -> f.getValue()));
  }

  /** Returns true if this descriptor references only a single, non-wildcard field. */
  public boolean referencesSingleField() {
    if (getAllFields()) {
      return false;
    }

    if (getFieldsAccessed().size() == 1 && getNestedFieldsAccessed().isEmpty()) {
      return true;
    }

    if (getFieldsAccessed().isEmpty() && getNestedFieldsAccessed().size() == 1) {
      return getNestedFieldsAccessed().values().iterator().next().referencesSingleField();
    }

    return false;
  }

  /**
   * Resolve the {@link FieldAccessDescriptor} against a schema.
   *
   * <p>Resolve will resolve all of the field names into field ids, validating that all field names
   * specified in the descriptor exist in the actual schema.
   */
  public FieldAccessDescriptor resolve(Schema schema) {
    List<FieldDescriptor> resolvedFieldIdsAccessed = resolveDirectFieldsAccessed(schema);
    Map<FieldDescriptor, FieldAccessDescriptor> resolvedNestedFieldsAccessed =
        resolveNestedFieldsAccessed(schema);

    checkState(
        !getAllFields() || resolvedNestedFieldsAccessed.isEmpty(),
        "nested fields cannot be set if allFields is also set");

    // If a recursive access is set for any nested fields, remove those fields from
    // fieldIdsAccessed.
    resolvedFieldIdsAccessed.removeAll(resolvedNestedFieldsAccessed.keySet());

    return builder()
        .setAllFields(getAllFields())
        .setFieldsAccessed(resolvedFieldIdsAccessed)
        .setNestedFieldsAccessed(resolvedNestedFieldsAccessed)
        .build();
  }

  private List<FieldDescriptor> resolveDirectFieldsAccessed(Schema schema) {
    List<FieldDescriptor> fields = new ArrayList<>();

    for (FieldDescriptor field : getFieldsAccessed()) {
      validateFieldDescriptor(schema, field);
      if (field.getFieldId() == null) {
        field = field.toBuilder().setFieldId(schema.indexOf(field.getFieldName())).build();
      }
      if (field.getFieldName() == null) {
        field = field.toBuilder().setFieldName(schema.nameOf(field.getFieldId())).build();
      }
      field = fillInMissingQualifiers(field, schema);
      fields.add(field);
    }

    if (!getFieldInsertionOrder()) {
      // Re-order fields based on field ID, rather than keeping them in insertion order
      Collections.sort(fields, Comparator.comparing(FieldDescriptor::getFieldId));
    }
    return fields;
  }

  private Map<FieldDescriptor, FieldAccessDescriptor> resolveNestedFieldsAccessed(Schema schema) {
    Map<FieldDescriptor, FieldAccessDescriptor> nestedFields;
    if (getFieldInsertionOrder()) {
      nestedFields = Maps.newLinkedHashMap();
    } else {
      Function<FieldDescriptor, Integer> extract =
          (Function<FieldDescriptor, Integer> & Serializable) FieldDescriptor::getFieldId;
      nestedFields = Maps.newTreeMap(Comparator.comparing(extract));
    }

    for (Map.Entry<FieldDescriptor, FieldAccessDescriptor> entry :
        getNestedFieldsAccessed().entrySet()) {
      FieldDescriptor fieldDescriptor = entry.getKey();
      FieldAccessDescriptor fieldAccessDescriptor = entry.getValue();
      validateFieldDescriptor(schema, fieldDescriptor);

      // Resolve the field id of the field that has nested access.
      if (entry.getKey().getFieldId() == null) {
        fieldDescriptor =
            fieldDescriptor
                .toBuilder()
                .setFieldId(schema.indexOf(fieldDescriptor.getFieldName()))
                .build();
      } else if (entry.getKey().getFieldName() == null) {
        fieldDescriptor =
            fieldDescriptor
                .toBuilder()
                .setFieldName(schema.nameOf(fieldDescriptor.getFieldId()))
                .build();
      }

      fieldDescriptor = fillInMissingQualifiers(fieldDescriptor, schema);
      // fieldType should now be the row we are selecting from, so recursively resolve it and
      // store the result in the list of resolved nested fields.
      fieldAccessDescriptor =
          fieldAccessDescriptor.resolve(getFieldDescriptorSchema(fieldDescriptor, schema));
      // We might still have duplicate FieldDescriptors, even if union was called earlier. Until
      //  resolving against an actual schema we might not have been to tell that two
      // FieldDescriptors were equivalent.
      nestedFields.merge(
          fieldDescriptor, fieldAccessDescriptor, (d1, d2) -> union(ImmutableList.of(d1, d2)));
    }

    return nestedFields;
  }

  private FieldDescriptor fillInMissingQualifiers(FieldDescriptor fieldDescriptor, Schema schema) {
    // If there are nested arrays or maps, walk down them until we find the next row. If there
    // are missing qualifiers, fill them in. This allows users to omit the qualifiers in the
    // simple case where they are all wildcards. For example, if a is a list of a list of row,
    // the user could select a[*][*].b, however we allow them to simply type a.b for brevity.
    FieldType fieldType = schema.getField(fieldDescriptor.getFieldId()).getType();
    Iterator<Qualifier> qualifierIt = fieldDescriptor.getQualifiers().iterator();
    List<Qualifier> qualifiers = Lists.newArrayList();
    while (fieldType.getTypeName().isCollectionType() || fieldType.getTypeName().isMapType()) {
      Qualifier qualifier = qualifierIt.hasNext() ? qualifierIt.next() : null;
      if (fieldType.getTypeName().isCollectionType()) {
        qualifier = (qualifier == null) ? Qualifier.of(ListQualifier.ALL) : qualifier;
        checkArgument(qualifier.getKind().equals(Qualifier.Kind.LIST));
        checkArgument(qualifier.getList().equals(ListQualifier.ALL));
        qualifiers.add(qualifier);
        fieldType = fieldType.getCollectionElementType();
      } else if (fieldType.getTypeName().isMapType()) {
        qualifier = (qualifier == null) ? Qualifier.of(MapQualifier.ALL) : qualifier;
        checkArgument(qualifier.getKind().equals(Qualifier.Kind.MAP));
        checkArgument(qualifier.getMap().equals(MapQualifier.ALL));
        qualifiers.add(qualifier);
        fieldType = fieldType.getMapValueType();
      }
    }
    return fieldDescriptor.toBuilder().setQualifiers(qualifiers).build();
  }

  private Schema getFieldDescriptorSchema(FieldDescriptor fieldDescriptor, Schema schema) {
    FieldType fieldType = schema.getField(fieldDescriptor.getFieldId()).getType();
    while (fieldType.getTypeName().isCollectionType() || fieldType.getTypeName().isMapType()) {
      if (fieldType.getTypeName().isCollectionType()) {
        fieldType = fieldType.getCollectionElementType();
      } else if (fieldType.getTypeName().isMapType()) {
        fieldType = fieldType.getMapValueType();
      }
    }
    return getFieldSchema(fieldType);
  }

  private static Schema getFieldSchema(FieldType type) {
    if (TypeName.ROW.equals(type.getTypeName())) {
      return type.getRowSchema();
    } else if (TypeName.ARRAY.equals(type.getTypeName())
        && TypeName.ROW.equals(type.getCollectionElementType().getTypeName())) {
      return type.getCollectionElementType().getRowSchema();
    } else if (TypeName.MAP.equals(type.getTypeName())
        && TypeName.ROW.equals(type.getMapValueType().getTypeName())) {
      return type.getMapValueType().getRowSchema();
    } else {
      throw new IllegalArgumentException(
          "FieldType " + type + " must be either a row or a container containing rows");
    }
  }

  private static void validateFieldDescriptor(Schema schema, FieldDescriptor fieldDescriptor) {
    Integer fieldId = fieldDescriptor.getFieldId();
    if (fieldId != null) {
      if (fieldId < 0 || fieldId >= schema.getFieldCount()) {
        throw new IllegalArgumentException("Invalid field id " + fieldId + " for schema " + schema);
      }
    }
    // If qualifiers were specified, validate them.
    // For example, if a selector was a[*][*], then a needs to be a List of a List.
    Field field =
        (fieldId != null)
            ? schema.getField(fieldId)
            : schema.getField(fieldDescriptor.getFieldName());
    FieldType fieldType = field.getType();
    for (Qualifier qualifier : fieldDescriptor.getQualifiers()) {
      switch (qualifier.getKind()) {
        case LIST:
          checkArgument(qualifier.getList().equals(ListQualifier.ALL));
          checkArgument(fieldType.getTypeName().equals(TypeName.ARRAY));
          fieldType = fieldType.getCollectionElementType();
          break;
        case MAP:
          checkArgument(qualifier.getMap().equals(MapQualifier.ALL));
          checkArgument(fieldType.getTypeName().equals(TypeName.MAP));
          fieldType = fieldType.getMapValueType();
          break;
        default:
          throw new IllegalStateException("Unexpected qualifier type " + qualifier.getKind());
      }
    }
  }

  @Override
  public String toString() {
    if (getAllFields()) {
      return "*";
    }

    List<String> singleSelectors =
        getFieldsAccessed().stream()
            .map(FieldDescriptor::getFieldName)
            .collect(Collectors.toList());
    List<String> nestedSelectors =
        getNestedFieldsAccessed().entrySet().stream()
            .map(e -> e.getKey().getFieldName() + "." + e.getValue().toString())
            .collect(Collectors.toList());
    ;
    return String.join(", ", Iterables.concat(singleSelectors, nestedSelectors));
  }
}
