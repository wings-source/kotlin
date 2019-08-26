/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.lower

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.ir.isOverridableOrOverrides
import org.jetbrains.kotlin.backend.common.lower.AbstractValueUsageTransformer
import org.jetbrains.kotlin.backend.wasm.utils.getWasmInlinedClass
import org.jetbrains.kotlin.backend.wasm.utils.isInlinedWasm
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder
import org.jetbrains.kotlin.ir.backend.js.utils.realOverrideTarget
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*


// Copied and adapted from Kotlin/Js and Kotlin/Native

class WasmAutoboxingTransformer(
    val context: CommonBackendContext,
    val boxIntrinsic: IrSimpleFunctionSymbol,
    val unboxIntrinsic: IrSimpleFunctionSymbol
) : AbstractValueUsageTransformer(context.irBuiltIns), FileLoweringPass {
    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid()

        // TODO: Track & insert parents for temporary variables
        irFile.patchDeclarationParents()
    }

    override fun IrExpression.useAs(type: IrType): IrExpression {

        val actualType = when (this) {
            is IrConstructorCall -> symbol.owner.returnType
            is IrCall -> symbol.owner.realOverrideTarget.returnType
            is IrGetField -> this.symbol.owner.type

            is IrGetValue -> {
                val value = this.symbol.owner
                if (value is IrValueParameter && value.shouldWasmValueParameterBeBoxed()) {
                    irBuiltIns.anyType
                } else {
                    this.type
                }
            }

            else -> this.type
        }

        // // TODO: Default parameters are passed as nulls and they need not to be unboxed. Fix this
        if (actualType.makeNotNull().isNothing())
            return this

        val expectedType = type

        if (expectedType.isNothing())
            return this

        if (expectedType.isUnit() || actualType.isUnit()) {
            return this
        }

        if (this is IrCall && this.symbol.owner.name.asString().contains("minus0")) {
            println("ACTUAL: ${actualType.render()}, EXPECTED: ${expectedType.render()}")
        }

        val actualInlinedClass = actualType.getWasmInlinedClass()
        val expectedInlinedClass = expectedType.getWasmInlinedClass()

        // Mimicking behaviour of current JS backend
        // TODO: Revisit
        if (
            (actualType is IrDynamicType && expectedType.makeNotNull().isChar()) ||
            (actualType.makeNotNull().isChar() && expectedType is IrDynamicType)
        ) return this

        val function = when {
            actualInlinedClass == null && expectedInlinedClass == null -> return this
            actualInlinedClass != null && expectedInlinedClass == null -> boxIntrinsic
            actualInlinedClass == null && expectedInlinedClass != null -> unboxIntrinsic
            else -> return this
        }

        return buildSafeCall(this, actualType, expectedType) { arg ->
            JsIrBuilder.buildCall(
                function,
                expectedType,
                typeArguments = listOf(actualType, expectedType)
            ).also {
                it.putValueArgument(0, arg)
            }
        }
    }

    private fun buildSafeCall(
        arg: IrExpression,
        actualType: IrType,
        resultType: IrType,
        call: (IrExpression) -> IrExpression
    ): IrExpression {
        if (!actualType.isNullable() || !resultType.isNullable())
            return call(arg)
        return JsIrBuilder.run {
            // TODO: Set parent of local variables
            val tmp = buildVar(actualType, parent = null, initializer = arg)
            val nullCheck = buildIfElse(
                type = resultType,
                cond = buildCall(irBuiltIns.eqeqeqSymbol).apply {
                    putValueArgument(0, buildGetValue(tmp.symbol))
                    putValueArgument(1, buildNull(irBuiltIns.nothingNType))
                },
                thenBranch = buildNull(irBuiltIns.nothingNType),
                elseBranch = call(buildGetValue(tmp.symbol))
            )
            buildBlock(
                type = resultType,
                statements = listOf(
                    tmp,
                    nullCheck
                )
            )
        }
    }

    private val IrFunctionAccessExpression.target: IrFunction
        get() = when (this) {
            is IrConstructorCall -> this.symbol.owner
            is IrDelegatingConstructorCall -> this.symbol.owner
            is IrCall -> this.callTarget
            else -> TODO(this.render())
        }

    private val IrCall.callTarget: IrFunction
        get() = symbol.owner.realOverrideTarget


    override fun IrExpression.useAsDispatchReceiver(expression: IrFunctionAccessExpression): IrExpression {
        return if (expression.symbol.owner.dispatchReceiverParameter?.shouldWasmValueParameterBeBoxed() == true)
            this.useAs(irBuiltIns.anyType)
        else
            this.useAsArgument(expression.target.dispatchReceiverParameter!!)
    }

    override fun IrExpression.useAsExtensionReceiver(expression: IrFunctionAccessExpression): IrExpression {
        return this.useAsArgument(expression.target.extensionReceiverParameter!!)
    }

    override fun IrExpression.useAsValueArgument(
        expression: IrFunctionAccessExpression,
        parameter: IrValueParameter
    ): IrExpression {
        return this.useAsArgument(expression.target.valueParameters[parameter.index])
    }

    override fun IrExpression.useAsVarargElement(expression: IrVararg): IrExpression {
        return this.useAs(
            // Do not box primitive inline classes
            if (this.type.isInlinedWasm() && !expression.type.isInlinedWasm() && !expression.type.isPrimitiveArray())
                irBuiltIns.anyNType
            else
                expression.varargElementType
        )
    }
}

val IrValueDeclaration.isDispatchReceiver: Boolean
    get() {
        val parent = this.parent
        if (parent is IrClass)
            return true
        if (parent is IrFunction && parent.dispatchReceiverParameter == this)
            return true
        return false
    }



// FIXME(Wasm): Move to Wasm-specific code
fun IrValueParameter.shouldWasmValueParameterBeBoxed(): Boolean {
    val function = this.parent as? IrSimpleFunction ?: return false
    val klass = function.parent as? IrClass ?: return false
    if (!klass.isInline) return false
    return this.isDispatchReceiver && function.isOverridableOrOverrides
}