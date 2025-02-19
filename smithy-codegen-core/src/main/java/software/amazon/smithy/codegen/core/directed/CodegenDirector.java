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

package software.amazon.smithy.codegen.core.directed;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.logging.Logger;
import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.codegen.core.CodegenContext;
import software.amazon.smithy.codegen.core.ImportContainer;
import software.amazon.smithy.codegen.core.SmithyIntegration;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.SymbolWriter;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.neighbor.Walker;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.NodeMapper;
import software.amazon.smithy.model.shapes.ResourceShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.model.transform.ModelTransformer;
import software.amazon.smithy.utils.CodeInterceptor;
import software.amazon.smithy.utils.CodeSection;
import software.amazon.smithy.utils.SmithyBuilder;

/**
 * Performs directed code generation of a {@link DirectedCodegen}.
 *
 * @param <W> Type of {@link SymbolWriter} used to generate code.
 * @param <I> Type of {@link SmithyIntegration} to apply.
 * @param <C> Type of {@link CodegenContext} to create and use.
 * @param <S> Type of settings object to pass to directed methods.
 */
public final class CodegenDirector<
        W extends SymbolWriter<W, ? extends ImportContainer>,
        I extends SmithyIntegration<S, W, C>,
        C extends CodegenContext<S, W>,
        S> {

    private static final Logger LOGGER = Logger.getLogger(DirectedCodegen.class.getName());

    private Class<I> integrationClass;
    private ShapeId service;
    private Model model;
    private S settings;
    private FileManifest fileManifest;
    private Supplier<Iterable<I>> integrationFinder;
    private DirectedCodegen<C, S> directedCodegen;
    private final List<BiFunction<Model, ModelTransformer, Model>> transforms = new ArrayList<>();

    /**
     * Simplifies a Smithy model for code generation of a single service.
     *
     * <ul>
     *     <li>Flattens error hierarchies onto every operation.</li>
     *     <li>Converts enum strings to enum shapes.</li>
     *     <li>Flattens mixins</li>
     * </ul>
     *
     * <p><em>Note</em>: This transform is applied automatically by a code
     * generator if {@link CodegenDirector#performDefaultCodegenTransforms()} is
     * set to true.
     *
     * @param model Model being code generated.
     * @param service Service being generated.
     * @param transformer Model transformer to use.
     * @return Returns the updated model.
     */
    public static Model simplifyModelForServiceCodegen(Model model, ShapeId service, ModelTransformer transformer) {
        ServiceShape serviceShape = model.expectShape(service, ServiceShape.class);
        model = transformer.copyServiceErrorsToOperations(model, serviceShape);
        // model = transformer.flattenAndRemoveMixins(model);
        model = transformer.copyServiceErrorsToOperations(model, serviceShape);
        // model = transformer.changeStringEnumsToEnumShapes(model, true);
        return model;
    }

    /**
     * Sets the required class used for SmithyIntegrations.
     *
     * @param integrationClass SmithyIntegration class.
     */
    public void integrationClass(Class<I> integrationClass) {
        this.integrationClass = integrationClass;
    }

    /**
     * Sets the required service being generated.
     *
     * @param service Service to generate.
     */
    public void service(ShapeId service) {
        this.service = service;
    }

    /**
     * Sets the required {@link DirectedCodegen} implementation to invoke.
     *
     * @param directedCodegen Directed code generator to run.
     */
    public void directedCodegen(DirectedCodegen<C, S> directedCodegen) {
        this.directedCodegen = directedCodegen;
    }

    /**
     * Sets the required model to generate from.
     *
     * @param model Model to generate from.
     */
    public void model(Model model) {
        this.model = model;
    }

    /**
     * Sets the required settings object used for code generation.
     *
     * @param settings Settings object.
     */
    public void settings(S settings) {
        this.settings = settings;
    }

    /**
     * Sets the required settings object used for code generation using
     * a {@link Node}.
     *
     * <p>A Node value is used by Smithy-Build plugins to configure settings.
     * This method is a helper method that uses Smithy's fairly simple
     * object-mapper to deserialize a node into the desired settings type.
     * You will need to manually deserialize your settings if using types that
     * are not supported by Smithy's {@link NodeMapper}.
     *
     * @param settingsType Settings type to deserialize into.
     * @param settingsNode Settings node value to deserialize.
     * @return Returns the deserialized settings as this is needed to provide a service shape ID.
     */
    public S settings(Class<S> settingsType, Node settingsNode) {
        LOGGER.fine(() -> "Loading codegen settings from node value: " + settingsNode.getSourceLocation());
        S deserialized = new NodeMapper().deserialize(settingsNode, settingsType);
        settings(deserialized);
        return deserialized;
    }

    /**
     * Sets the required file manifest used to write files to disk.
     *
     * @param fileManifest File manifest to write files.
     */
    public void fileManifest(FileManifest fileManifest) {
        this.fileManifest = fileManifest;
    }

    /**
     * Sets a custom implementation for finding {@link SmithyIntegration}s.
     *
     * <p>Most implementations can use {@link #integrationClassLoader(ClassLoader)}.
     *
     * @param integrationFinder Smithy integration finder.
     */
    public void integrationFinder(Supplier<Iterable<I>> integrationFinder) {
        this.integrationFinder = integrationFinder;
    }

    /**
     * Sets a custom class loader for finding implementations of {@link SmithyIntegration}.
     *
     * @param classLoader Class loader to find integrations.* @return Returns self.
     */
    public void integrationClassLoader(ClassLoader classLoader) {
        Objects.requireNonNull(integrationClass,
                               "integrationClass() must be called before calling integrationClassLoader");
        integrationFinder(() -> ServiceLoader.load(integrationClass, classLoader));
    }

    /**
     * Set to true to apply {@link CodegenDirector#simplifyModelForServiceCodegen}
     * prior to code generation.
     */
    public void performDefaultCodegenTransforms() {
        transforms.add((model, transformer) -> {
            LOGGER.finest("Performing default codegen model transforms for directed codegen");
            return simplifyModelForServiceCodegen(model, Objects.requireNonNull(service), transformer);
        });
    }

    /**
     * Generates dedicated input and output shapes for every operation if the operation
     * doesn't already have them.
     *
     * <p>This method uses "Input" as the default suffix for input, and "Output" as the
     * default suffix for output shapes. Use {@link #createDedicatedInputsAndOutputs(String, String)}
     * to use custom suffixes.
     *
     * @see ModelTransformer#createDedicatedInputAndOutput(Model, String, String)
     */
    public void createDedicatedInputsAndOutputs() {
        createDedicatedInputsAndOutputs("Input", "Output");
    }

    /**
     * Generates dedicated input and output shapes for every operation if the operation
     * doesn't already have them.
     *
     * @param inputSuffix  Suffix to use for input shapes (e.g., "Input").
     * @param outputSuffix Suffix to use for output shapes (e.g., "Output").
     * @see ModelTransformer#createDedicatedInputAndOutput(Model, String, String)
     */
    public void createDedicatedInputsAndOutputs(String inputSuffix, String outputSuffix) {
        transforms.add((model, transformer) -> {
            LOGGER.finest("Creating dedicated input and output shapes for directed codegen");
            return transformer.createDedicatedInputAndOutput(model, inputSuffix, outputSuffix);
        });
    }

    /**
     * Sorts all members of the model prior to codegen.
     *
     * <p>This should only be used by languages where changing the order of members
     * in a structure or union is a backward compatible change (i.e., not C, C++, Rust, etc).
     * Once this is performed, there's no need to ever explicitly sort members
     * throughout the rest of code generation.
     */
    public void sortMembers() {
        transforms.add((model, transformer) -> {
            LOGGER.finest("Sorting model members for directed codegen");
            return transformer.sortMembers(model, Shape::compareTo);
        });
    }

    /**
     * Finalizes the Runner and performs directed code generation.
     *
     * @throws IllegalStateException if a required value has not been provided.
     */
    public void run() {
        validateState();
        performModelTransforms();

        List<I> integrations = findIntegrations();
        preprocessModelWithIntegrations(integrations);

        ServiceShape serviceShape = model.expectShape(service, ServiceShape.class);
        Set<Shape> shapes = new Walker(model).walkShapes(serviceShape);

        SymbolProvider provider = createSymbolProvider(integrations, serviceShape);

        C context = createContext(serviceShape, provider);

        registerInterceptors(context, integrations);

        LOGGER.finest(() -> "Generating service " + serviceShape.getId());
        directedCodegen.generateService(new GenerateServiceDirective<>(context, serviceShape));

        generateShapesInService(context, serviceShape, shapes);

        CustomizeDirective<C, S> postProcess = new CustomizeDirective<>(context, serviceShape);

        LOGGER.finest(() -> "Performing custom codegen for "
                            + directedCodegen.getClass().getName() + " before integrations");
        directedCodegen.customizeBeforeIntegrations(postProcess);

        applyIntegrationCustomizations(context, integrations);

        LOGGER.finest(() -> "Performing custom codegen for "
                            + directedCodegen.getClass().getName() + " after integrations");
        directedCodegen.customizeAfterIntegrations(postProcess);

        LOGGER.finest(() -> "Directed codegen finished for " + directedCodegen.getClass().getName());

        if (!context.writerDelegator().getWriters().isEmpty()) {
            LOGGER.info(() -> "Flushing remaining writers of " + directedCodegen.getClass().getName());
            context.writerDelegator().flushWriters();
        }
    }

    private void validateState() {
        SmithyBuilder.requiredState("integrationClass", integrationClass);
        SmithyBuilder.requiredState("service", service);
        SmithyBuilder.requiredState("model", model);
        SmithyBuilder.requiredState("settings", settings);
        SmithyBuilder.requiredState("fileManifest", fileManifest);
        SmithyBuilder.requiredState("directedCodegen", directedCodegen);

        // Use a default integration finder implementation.
        if (integrationFinder == null) {
            LOGGER.fine(() -> String.format("Finding %s integrations using the %s class loader",
                                            integrationClass.getName(),
                                            CodegenDirector.class.getCanonicalName()));
            integrationClassLoader(getClass().getClassLoader());
        }
    }

    private void performModelTransforms() {
        LOGGER.fine(() -> "Performing model transformations for " + directedCodegen.getClass().getName());
        ModelTransformer transformer = ModelTransformer.create();
        for (BiFunction<Model, ModelTransformer, Model> transform : transforms) {
            model = transform.apply(model, transformer);
        }
    }

    private List<I> findIntegrations() {
        LOGGER.fine(() -> "Finding integration implementations of " + integrationClass.getName());
        List<I> integrations = SmithyIntegration.sort(integrationFinder.get());
        integrations.forEach(i -> LOGGER.finest(() -> "Found integration " + i.getClass().getCanonicalName()));
        return integrations;
    }

    private void preprocessModelWithIntegrations(List<I> integrations) {
        LOGGER.fine(() -> "Preprocessing codegen model using " + integrationClass.getName());
        for (I integration : integrations) {
            model = integration.preprocessModel(model, settings);
        }
        LOGGER.finer(() -> "Preprocessing codegen model using " + integrationClass.getName() + " complete");
    }

    private SymbolProvider createSymbolProvider(List<I> integrations, ServiceShape serviceShape) {
        LOGGER.fine(() -> "Creating a symbol provider from " + settings.getClass().getName());
        SymbolProvider provider = directedCodegen.createSymbolProvider(
                new CreateSymbolProviderDirective<>(model, settings, serviceShape));

        LOGGER.finer(() -> "Decorating symbol provider using " + integrationClass.getName());
        for (I integration : integrations) {
            provider = integration.decorateSymbolProvider(model, settings, provider);
        }

        return provider;
    }

    private C createContext(ServiceShape serviceShape, SymbolProvider provider) {
        LOGGER.fine(() -> "Creating a codegen context for " + directedCodegen.getClass().getName());
        return directedCodegen.createContext(new CreateContextDirective<>(
                model, settings, serviceShape, provider, fileManifest));
    }

    private void registerInterceptors(C context, List<I> integrations) {
        LOGGER.fine(() -> "Registering CodeInterceptors from integrations of " + integrationClass.getName());
        List<CodeInterceptor<? extends CodeSection, W>> interceptors = new ArrayList<>();
        for (I integration : integrations) {
            interceptors.addAll(integration.interceptors(context));
        }
        context.writerDelegator().setInterceptors(interceptors);
    }

    private void generateShapesInService(C context, ServiceShape serviceShape, Set<Shape> shapes) {
        LOGGER.fine(() -> "Generating shapes for " + directedCodegen.getClass().getName());
        generateResourceShapes(context, serviceShape, shapes);
        generateStructures(context, serviceShape, shapes);
        generateUnionShapes(context, serviceShape, shapes);
        generateEnumShapes(context, serviceShape, shapes);
        LOGGER.finest(() -> "Finished generating shapes for " + directedCodegen.getClass().getName());
    }

    private void generateResourceShapes(C context, ServiceShape serviceShape, Set<Shape> shapes) {
        for (ResourceShape shape : model.getResourceShapes()) {
            if (shapes.contains(shape)) {
                LOGGER.finest(() -> "Generating resource " + shape.getId());
                directedCodegen.generateResource(
                        new GenerateResourceDirective<>(context, serviceShape, shape));
            }
        }
    }

    private void generateStructures(C context, ServiceShape serviceShape, Set<Shape> shapes) {
        for (StructureShape shape : model.getStructureShapes()) {
            if (shapes.contains(shape)) {
                if (shape.hasTrait(ErrorTrait.class)) {
                    LOGGER.finest(() -> "Generating error " + shape.getId());
                    directedCodegen.generateError(new GenerateErrorDirective<>(context, serviceShape, shape));
                } else {
                    LOGGER.finest(() -> "Generating structure " + shape.getId());
                    directedCodegen.generateStructure(new GenerateStructureDirective<>(context, serviceShape, shape));
                }
            }
        }
    }

    private void generateUnionShapes(C context, ServiceShape serviceShape, Set<Shape> shapes) {
        for (UnionShape shape : model.getUnionShapes()) {
            if (shapes.contains(shape)) {
                LOGGER.finest(() -> "Generating union " + shape.getId());
                directedCodegen.generateUnion(new GenerateUnionDirective<>(context, serviceShape, shape));
            }
        }
    }

    private void generateEnumShapes(C context, ServiceShape serviceShape, Set<Shape> shapes) {
        // Generate enum shapes connected to the service.
        for (StringShape shape : model.getStringShapesWithTrait(EnumTrait.class)) {
            if (shapes.contains(shape)) {
                LOGGER.finest(() -> "Generating string enum " + shape.getId());
                directedCodegen.generateEnumShape(new GenerateEnumDirective<>(context, serviceShape, shape));
            }
        }

        /*
        TODO: uncomment in idl-2.0
        for (EnumShape shape : model.getEnumShapes()) {
            if (shapes.contains(shape)) {
                LOGGER.finest(() -> "Generating enum " + shape.getId());
                directedCodegen.generateEnumShape(new GenerateEnumContext<>(context, serviceShape, shape));
            }
        }
        */
    }

    private void applyIntegrationCustomizations(C context, List<I> integrations) {
        for (I integration : integrations) {
            LOGGER.finest(() -> "Customizing codegen for " + directedCodegen.getClass().getName()
                                + " using integration " + integration.getClass().getName());
            integration.customize(context);
        }
    }
}
