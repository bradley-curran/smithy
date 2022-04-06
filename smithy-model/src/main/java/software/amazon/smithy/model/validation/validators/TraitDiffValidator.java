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
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.NodePointer;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.SetShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeVisitor;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.TraitDefinition;
import software.amazon.smithy.model.validation.AbstractValidator;
import software.amazon.smithy.model.validation.ValidationEvent;

/**
 * Validates that the JSON pointer paths of the diff property of a trait refers
 * to valid parts of the model.
 */
public final class TraitDiffValidator extends AbstractValidator {
    @Override
    public List<ValidationEvent> validate(Model model) {
        List<ValidationEvent> events = new ArrayList<>();
        for (Shape shape : model.getShapesWithTrait(TraitDefinition.class)) {
            validateTrait(model, shape, events);
        }
        return events;
    }

    private void validateTrait(Model model, Shape shape, List<ValidationEvent> events) {
        TraitDefinition trait = shape.expectTrait(TraitDefinition.class);

        int position = 0;
        for (TraitDefinition.DiffRule diffRule : trait.getBreakingChanges()) {
            // No need to validate empty paths or paths that are "", meaning the entire trait.
            if (diffRule.getPath().isPresent() && !diffRule.getPath().get().toString().equals("")) {
                Shape current = shape;
                NodePointer pointer = diffRule.getPath().get();
                int segment = 0;
                DiffVisitor visitor = new DiffVisitor(events, model, shape);
                for (String part : pointer.getParts()) {
                    visitor.updateState(position, segment, part);
                    current = current.accept(visitor);
                    if (current == null) {
                        break;
                    }
                    segment++;
                }
            }

            position++;
        }
    }

    private ValidationEvent emit(
            Shape shape,
            int element,
            int segment,
            String message
    ) {
        TraitDefinition definition = shape.expectTrait(TraitDefinition.class);
        NodePointer path = definition.getBreakingChanges().get(element).getPath().get();
        return error(shape, definition, String.format(
                "Invalid breakingChanges element %d, '%s', at segment '%s': %s",
                element, path, path.getParts().get(segment), message));
    }

    private final class DiffVisitor extends ShapeVisitor.Default<Shape> {
        private final List<ValidationEvent> events;
        private final Model model;
        private final Shape traitShape;
        private int diffPosition;
        private int segment;
        private String part;

        DiffVisitor(List<ValidationEvent> events, Model model, Shape traitShape) {
            this.events = events;
            this.model = model;
            this.traitShape = traitShape;
        }

        void updateState(int diffPosition, int segment, String part) {
            this.diffPosition = diffPosition;
            this.segment = segment;
            this.part = part;
        }

        @Override
        protected Shape getDefault(Shape shape) {
            events.add(emit(traitShape, diffPosition, segment, String.format(
                    "Cannot traverse into %s, a %s shape", shape.getId(), shape.getType())));
            return null;
        }

        @Override
        public Shape listShape(ListShape shape) {
            return listAndSet(shape, shape.getMember());
        }

        @Override
        public Shape setShape(SetShape shape) {
            return listAndSet(shape, shape.getMember());
        }

        private Shape listAndSet(Shape shape, MemberShape member) {
            if (!part.equals("*")) {
                events.add(emit(traitShape, diffPosition, segment, String.format(
                        "%s, a %s, can only be referred to using '*' segments",
                        shape.getId(), shape.getType())));
                return null;
            } else {
                return model.expectShape(member.getTarget());
            }
        }

        @Override
        public Shape mapShape(MapShape shape) {
            if (part.equals("key")) {
                return model.expectShape(shape.getKey().getTarget());
            } else if (part.equals("value")) {
                return model.expectShape(shape.getValue().getTarget());
            } else {
                events.add(emit(traitShape, diffPosition, segment, "Map shapes only support JSON pointer values "
                                                                   + "'key' or 'value'"));
                return null;
            }
        }

        @Override
        public Shape structureShape(StructureShape shape) {
            if (!shape.getAllMembers().containsKey(part)) {
                events.add(emit(traitShape, diffPosition, segment, "Unknown structure member"));
                return null;
            }
            return model.expectShape(shape.getAllMembers().get(part).getTarget());
        }

        @Override
        public Shape unionShape(UnionShape shape) {
            if (!shape.getAllMembers().containsKey(part)) {
                events.add(emit(traitShape, diffPosition, segment, "Unknown union member"));
                return null;
            }
            return model.expectShape(shape.getAllMembers().get(part).getTarget());
        }
    }
}
