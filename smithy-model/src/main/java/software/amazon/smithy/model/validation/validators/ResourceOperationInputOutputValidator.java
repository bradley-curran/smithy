/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.model.validation.validators;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ResourceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.PropertyTrait;
import software.amazon.smithy.model.traits.TransientTrait;
import software.amazon.smithy.model.validation.AbstractValidator;
import software.amazon.smithy.model.validation.ValidationEvent;

/**
 * Validates that resource are applied appropriately to resources.
 */
public final class ResourceOperationInputOutputValidator extends AbstractValidator {

    @Override
    public List<ValidationEvent> validate(Model model) {
        return model.shapes(ResourceShape.class)
                .filter(ResourceShape::hasProperties)
                .flatMap(shape -> validateResource(model, shape).stream())
                .collect(Collectors.toList());
    }

    private List<ValidationEvent> validateResource(Model model, ResourceShape resource) {
        List<ValidationEvent> events = new ArrayList<>();

        Set<String> propertiesInOperations = new TreeSet<>();

        resource.getPut().flatMap(model::getShape).flatMap(Shape::asOperationShape).ifPresent(operation -> {
            propertiesInOperations.addAll(getAllOperationProperties(model, operation));
            validateTopLevelMembersToProperties(model, resource, operation, true, "put", events);
        });

        resource.getCreate().flatMap(model::getShape).flatMap(Shape::asOperationShape).ifPresent(operation -> {
            propertiesInOperations.addAll(getAllOperationProperties(model, operation));
            validateTopLevelMembersToProperties(model, resource, operation, true, "create", events);
        });

        resource.getRead().flatMap(model::getShape).flatMap(Shape::asOperationShape).ifPresent(operation -> {
            propertiesInOperations.addAll(getAllOperationProperties(model, operation));
            validateTopLevelMembersToProperties(model, resource, operation, false, "read", events);
        });

        resource.getUpdate().flatMap(model::getShape).flatMap(Shape::asOperationShape).ifPresent(operation -> {
            propertiesInOperations.addAll(getAllOperationProperties(model, operation));
            validateTopLevelMembersToProperties(model, resource, operation, true, "update", events);
        });

        resource.getDelete().flatMap(model::getShape).flatMap(Shape::asOperationShape).ifPresent(operation -> {
            propertiesInOperations.addAll(getAllOperationProperties(model, operation));
            validateTopLevelMembersToProperties(model, resource, operation, true, "delete", events);
        });

        resource.getList().flatMap(model::getShape).flatMap(Shape::asOperationShape).ifPresent(operation -> {
            propertiesInOperations.addAll(getAllOperationProperties(model, operation));
            validateTopLevelMembersToProperties(model, resource, operation, true, "list", events);
        });

        Set<String> definedProperties = resource.getProperties().keySet();
        definedProperties.retainAll(propertiesInOperations);
        for (String propertyNotInLifecycleOp : definedProperties) {
            events.add(error(resource, String.format("Resource shape's `%s` property which is not an input or output"
                     + " for any lifecycle operation.", propertyNotInLifecycleOp)));
        }

        return events;
    }

    private List<String> getAllOperationProperties(Model model, OperationShape operation) {
        List<String> properties = new ArrayList<>();

        operation.getInput().flatMap(model::getShape).flatMap(Shape::asStructureShape).ifPresent(structure -> {
            for (MemberShape member : structure.members()) {
                properties.add(getPropertyNameFromMember(member));
            }
        });
        operation.getOutput().flatMap(model::getShape).flatMap(Shape::asStructureShape).ifPresent(structure -> {
            for (MemberShape member : structure.members()) {
                properties.add(getPropertyNameFromMember(member));
            }
        });
        return properties;
    }

    private String getPropertyNameFromMember(MemberShape member) {
        return member.getTrait(PropertyTrait.class)
                    .flatMap(trait -> trait.getName()).orElse(member.getMemberName());
    }

    private void validateTopLevelMembersToProperties(
        Model model,
        ResourceShape resource,
        OperationShape operation,
        boolean areOutputProperties,
        String lifecycleOperationName,
        List<ValidationEvent> events
    ) {
        Optional<ShapeId> topLevelShapeId =
                areOutputProperties
                ? operation.getOutput()
                : operation.getInput();
        Map<String, ShapeId> properties = resource.getProperties();
        Map<String, Set<MemberShape>> propertyToMemberMappings = new TreeMap<>();

        Optional<StructureShape> topLevelShape = topLevelShapeId
                        .flatMap(model::getShape).flatMap(Shape::asStructureShape);
        topLevelShape.ifPresent(shape -> {
            for (MemberShape member : shape.members()) {
                if (!member.hasTrait(TransientTrait.class)) {
                    String propertyName = getPropertyNameFromMember(member);
                    propertyToMemberMappings.computeIfAbsent(propertyName,
                        m -> new TreeSet<>()).add(member);

                    if (properties.containsKey(propertyName)) {
                        if (!properties.get(propertyName).equals(member.getTarget())) {
                            events.add(error(resource, String.format("The resource property `%s` has a conflicting"
                                + " target shape `%s` with the `%s` lifecycle operation which targets `%s`.",
                                propertyName, properties)));
                        }
                    } else {
                        events.add(error(member, String.format("Member `%s` targets property `%s` which is not"
                            + " specified in the resource properties properties block.", member.getMemberName(),
                            propertyName)));
                    }
                }
            }

            for (Map.Entry<String, Set<MemberShape>> entry : propertyToMemberMappings.entrySet()) {
                if (entry.getValue().size() > 1) {
                    events.add(error(shape, String.format(
                            "This shape contains members with conflicting property names that resolve to '%s': %s",
                            entry.getKey(),
                            entry.getValue().stream().map(MemberShape::getMemberName)
                                .collect(Collectors.joining(", ")))));
                }
            }
        });
    }
}
