/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is free software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

package cn.lliiooll.processors

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Native replacement for the legacy third-party KSP library
 * `com.github.livefront.sealed-enum`, which was only available from JitPack.
 *
 * For every sealed class whose companion object (or the sealed class itself) is
 * annotated with `@io.github.qauxv.base.annotation.GenSealedEnum`, this processor
 * generates a `<SealedClassName>SealedEnum` object in the same package as the
 * sealed class, providing:
 *
 * - `values: List<T>` - every object instance in the sealed hierarchy, in a
 *   deterministic (sorted by qualified name) order;
 * - `nameOf(obj)` / `ordinalOf(obj)` - name and ordinal lookup;
 * - `valueOf(name)` - reverse lookup by name.
 *
 * It also generates the top-level `values` / `name` / `ordinal` extensions and
 * the companion `valueOf` function, matching the API surface of the old library
 * so that no call sites need to change.
 */
class SealedEnumProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    companion object {
        private const val ANNOTATION_NAME = "io.github.qauxv.base.annotation.GenSealedEnum"
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(ANNOTATION_NAME)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        if (symbols.isEmpty()) {
            return emptyList()
        }
        logger.info("SealedEnumProcessor start.")
        symbols.forEach { annotated ->
            val sealedClass = resolveSealedClass(annotated)
            if (sealedClass == null) {
                logger.error(
                    "@GenSealedEnum must be applied to the companion object of a sealed class",
                    annotated
                )
            } else {
                generateForSealedClass(sealedClass)
            }
        }
        logger.info("SealedEnumProcessor count = ${symbols.size}.")
        return emptyList()
    }

    private fun resolveSealedClass(annotated: KSClassDeclaration): KSClassDeclaration? {
        if (annotated.isCompanionObject) {
            val parent = annotated.parentDeclaration as? KSClassDeclaration ?: return null
            return parent.takeIf { Modifier.SEALED in it.modifiers }
        }
        return annotated.takeIf { Modifier.SEALED in it.modifiers }
    }

    private fun collectObjectLeaves(decl: KSClassDeclaration, leaves: MutableList<KSClassDeclaration>) {
        when {
            decl.classKind == ClassKind.OBJECT -> leaves += decl
            Modifier.SEALED in decl.modifiers -> decl.getSealedSubclasses().forEach { collectObjectLeaves(it, leaves) }
            else -> logger.error(
                "Sealed subclass ${decl.qualifiedName?.asString() ?: decl.simpleName.asString()} " +
                    "is neither an object nor a sealed class, cannot include it in the generated sealed enum",
                decl
            )
        }
    }

    private fun generateForSealedClass(sealedClass: KSClassDeclaration) {
        val sealedClassName = sealedClass.toClassName()
        val packageName = sealedClass.packageName.asString()
        val sealedSimpleName = sealedClass.simpleName.asString()
        val companionClassName = sealedClassName.nestedClass("Companion")
        val sealedEnumObjectName = ClassName(packageName, "${sealedSimpleName}SealedEnum")

        val leaves = ArrayList<KSClassDeclaration>()
        collectObjectLeaves(sealedClass, leaves)
        val sortedLeaves = leaves.distinct()
            .sortedBy { it.qualifiedName?.asString() ?: it.simpleName.asString() }
        if (sortedLeaves.isEmpty()) {
            logger.error(
                "Sealed class ${sealedClass.qualifiedName?.asString()} has no object subclasses, skipping",
                sealedClass
            )
            return
        }

        val valuesType = LIST.parameterizedBy(sealedClassName)

        val valuesProperty = PropertySpec.builder("values", valuesType)
            .initializer(
                CodeBlock.of("listOf(\n%L\n)", sortedLeaves.map { CodeBlock.of("%T", it.toClassName()) }.joinToCode(",\n"))
            )
            .build()

        val nameOfFun = FunSpec.builder("nameOf")
            .addParameter("obj", sealedClassName)
            .returns(String::class)
            .apply {
                beginControlFlow("return when (obj)")
                sortedLeaves.forEach { leaf ->
                    addStatement("%T -> %S", leaf.toClassName(), leaf.simpleName.asString())
                }
                endControlFlow()
            }
            .build()

        val ordinalOfFun = FunSpec.builder("ordinalOf")
            .addParameter("obj", sealedClassName)
            .returns(Int::class)
            .addStatement("return values.indexOf(obj)")
            .build()

        val valueOfFun = FunSpec.builder("valueOf")
            .addParameter("name", String::class)
            .returns(sealedClassName)
            .apply {
                beginControlFlow("return when (name)")
                sortedLeaves.forEach { leaf ->
                    addStatement("%S -> %T", leaf.simpleName.asString(), leaf.toClassName())
                }
                addStatement("else -> throw IllegalArgumentException(%S + name)", "No such $sealedSimpleName name: ")
                endControlFlow()
            }
            .build()

        val sealedEnumObject = TypeSpec.objectBuilder(sealedEnumObjectName)
            .addProperty(valuesProperty)
            .addFunction(nameOfFun)
            .addFunction(ordinalOfFun)
            .addFunction(valueOfFun)
            .build()

        val companionValuesProperty = PropertySpec.builder("values", valuesType)
            .receiver(companionClassName)
            .getter(FunSpec.getterBuilder().addStatement("return %T.values", sealedEnumObjectName).build())
            .build()

        val nameExtensionProperty = PropertySpec.builder("name", String::class)
            .receiver(sealedClassName)
            .getter(FunSpec.getterBuilder().addStatement("return %T.nameOf(this)", sealedEnumObjectName).build())
            .build()

        val ordinalExtensionProperty = PropertySpec.builder("ordinal", Int::class)
            .receiver(sealedClassName)
            .getter(FunSpec.getterBuilder().addStatement("return %T.ordinalOf(this)", sealedEnumObjectName).build())
            .build()

        val companionValueOfFun = FunSpec.builder("valueOf")
            .receiver(companionClassName)
            .addParameter("name", String::class)
            .returns(sealedClassName)
            .addStatement("return %T.valueOf(name)", sealedEnumObjectName)
            .build()

        val fileSpec = FileSpec.builder(packageName, "${sealedSimpleName}SealedEnum")
            .addFileComment("Generated by SealedEnumProcessor, do not edit manually.")
            .addType(sealedEnumObject)
            .addProperty(companionValuesProperty)
            .addProperty(nameExtensionProperty)
            .addProperty(ordinalExtensionProperty)
            .addFunction(companionValueOfFun)
            .build()

        val containingFile = sealedClass.containingFile
        val dependencies = if (containingFile != null) {
            Dependencies(true, containingFile)
        } else {
            Dependencies(true)
        }
        fileSpec.writeTo(codeGenerator, dependencies)
    }
}

class SealedEnumProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return SealedEnumProcessor(environment.codeGenerator, environment.logger)
    }
}
