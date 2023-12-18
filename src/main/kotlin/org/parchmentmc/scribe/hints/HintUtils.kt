/*
 * Scribe
 * Copyright (C) 2023 ParchmentMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.parchmentmc.scribe.hints

import com.intellij.codeInsight.completion.CompletionMemory
import com.intellij.codeInsight.completion.JavaMethodCallElement
import com.intellij.codeInsight.hints.HintWidthAdjustment
import com.intellij.codeInsight.hints.InlayInfo
import com.intellij.codeInsight.hints.isParameterHintsEnabledForLanguage
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.*
import com.intellij.psi.impl.source.resolve.graphInference.PsiPolyExpressionUtil
import com.intellij.psi.impl.source.tree.java.PsiEmptyExpressionImpl
import com.intellij.psi.impl.source.tree.java.PsiMethodCallExpressionImpl
import com.intellij.psi.impl.source.tree.java.PsiNewExpressionImpl
import com.intellij.psi.util.TypeConversionUtil
import com.intellij.util.IncorrectOperationException
import com.siyeh.ig.callMatcher.CallMatcher
import org.parchmentmc.feather.mapping.MappingDataContainer.MethodData
import org.parchmentmc.scribe.ParchmentMappings
import org.parchmentmc.scribe.util.jvmIndex
import java.util.*

internal object JavaInlayHintsProvider {
    fun hints(callExpression: PsiCall): Set<InlayInfo> {
        if (JavaMethodCallElement.isCompletionMode(callExpression)) {
            val argumentList = callExpression.argumentList ?: return emptySet()
            val text = argumentList.text
            if (text == null || !text.startsWith('(') || !text.endsWith(')')) return emptySet()

            val method = CompletionMemory.getChosenMethod(callExpression) ?: return emptySet()

            val params = method.parameterList.parameters
            val methodData = ParchmentMappings.getInstance(callExpression.project)
                .getMethodData(method, false, true)
            val paramNames = params.map {
                methodData?.getParameter(it.jvmIndex)?.name ?: it.name
            }

            val arguments = argumentList.expressions
            val limit = JavaMethodCallElement.getCompletionHintsLimit()
            val trailingOffset = argumentList.textRange.endOffset - 1

            val infos = ArrayList<InlayInfo>()
            var lastIndex = 0
            (if (arguments.isEmpty()) listOf(trailingOffset) else arguments.map { inlayOffset(it) }).forEachIndexed { i, offset ->
                if (i < params.size) {
                    paramNames[i].let {
                        infos.add(InlayInfo(it, offset, false, params.size == 1, false))
                    }
                    lastIndex = i
                }
            }
            if (Registry.`is`("editor.completion.hints.virtual.comma")) {
                for (i in lastIndex + 1 until minOf(params.size, limit)) {
                    paramNames[i].let {
                        infos.add(createHintWithComma(it, trailingOffset))
                    }
                    lastIndex = i
                }
            }
            if (method.isVarArgs && (arguments.isEmpty() && params.size == 2 || arguments.isNotEmpty() && arguments.size == params.size - 1)) {
                paramNames[params.size - 1].let {
                    infos.add(createHintWithComma(it, trailingOffset))
                }
            }
            else if (Registry.`is`("editor.completion.hints.virtual.comma") && lastIndex < (params.size - 1) ||
                limit == 1 && arguments.isEmpty() && params.size > 1 ||
                limit <= arguments.size && arguments.size < params.size) {
                infos.add(InlayInfo("...more", trailingOffset, false, false, true))
            }
            return infos.toSet()
        }

        if (!isParameterHintsEnabledForLanguage(callExpression.language)) return emptySet()

        val resolveResult = callExpression.resolveMethodGenerics()
        val hints = methodHints(callExpression, resolveResult)
        if (hints.isNotEmpty()) return hints

        return when (callExpression) {
            is PsiMethodCallExpressionImpl ->
                mergedHints(callExpression, callExpression.methodExpression.multiResolve(false))
            is PsiNewExpressionImpl ->
                mergedHints(callExpression, callExpression.constructorFakeReference.multiResolve(false))
            else -> emptySet()
        }
    }

    private fun createHintWithComma(parameterName: String, offset: Int): InlayInfo {
        return InlayInfo(",$parameterName", offset, false, false, true,
            HintWidthAdjustment(", ", parameterName, 1)
        )
    }

    private fun mergedHints(callExpression: PsiCallExpression,
                            results: Array<out ResolveResult>): Set<InlayInfo> {
        val resultSet = results
            .filter { it.element != null }
            .map { methodHints(callExpression, it) }

        if (resultSet.isEmpty()) return emptySet()
        if (resultSet.size == 1) {
            return resultSet.first()
        }

        val chosenMethod: PsiMethod? = CompletionMemory.getChosenMethod(callExpression)
        if (chosenMethod != null) {
            val callInfo = callInfo(callExpression, chosenMethod, ParchmentMappings.getInstance(callExpression.project)
                .getMethodData(chosenMethod, false, true))
            return hintSet(callInfo, PsiSubstitutor.EMPTY)
        }

        //we can show hints for same named parameters of overloaded methods, even if you don't know exact method
        return resultSet.reduce { left, right -> left.intersect(right) }
            .map { InlayInfo(it.text, it.offset, isShowOnlyIfExistedBefore = true) }
            .toSet()
    }

    private fun methodHints(callExpression: PsiCall, resolveResult: ResolveResult): Set<InlayInfo> {
        val element = resolveResult.element
        val substitutor = (resolveResult as? JavaResolveResult)?.substitutor ?: PsiSubstitutor.EMPTY

        if (element is PsiMethod) {
            val data = ParchmentMappings.getInstance(element.project).getMethodData(element, false, true)
            if (isMethodToShow(element, data)) {
                val info = callInfo(callExpression, element, data)
                if (isCallInfoToShow(info)) {
                    return hintSet(info, substitutor)
                }
            }
        }

        return emptySet()
    }

    private fun isCallInfoToShow(info: CallInfo): Boolean {
        val hintsProvider = InlayParamHints.getInstance()
        if (!hintsProvider.default.ignoreOneCharOneDigitHints.get() && info.allParamsSequential()) {
            return false
        }
        return true
    }

    private fun String.decomposeOrderedParams(): Pair<String, Int>? {
        val firstDigit = indexOfFirst { it.isDigit() }
        if (firstDigit < 0) return null

        val prefix = substring(0, firstDigit)
        try {
            val number = substring(firstDigit, length).toInt()
            return prefix to number
        }
        catch (e: NumberFormatException) {
            return null
        }
    }

    private fun CallInfo.allParamsSequential(): Boolean {
        val paramNames = regularArgs.mapNotNull { it.parameter.name.decomposeOrderedParams() }

        if (paramNames.size > 1 && paramNames.size == regularArgs.size) {
            val prefixes = paramNames.map { it.first }
            if (prefixes.toSet().size != 1) return false

            val numbers = paramNames.map { it.second }
            val first = numbers.first()
            if (first == 0 || first == 1) {
                return numbers.areSequential()
            }
        }

        return false
    }

    private fun hintSet(info: CallInfo, substitutor: PsiSubstitutor): Set<InlayInfo> {
        val resultSet = mutableSetOf<InlayInfo>()

        val varargInlay = info.varargsInlay(substitutor, info.data)
        if (varargInlay != null) {
            resultSet.add(varargInlay)
        }

        if (isShowForParamsWithSameType()) {
            resultSet.addAll(info.sameTypeInlays())
        }

        resultSet.addAll(info.unclearInlays(substitutor))

        return resultSet
    }

    private fun isShowForParamsWithSameType() = InlayParamHints.getInstance().default.showForParamsWithSameType.get()

    private fun isMethodToShow(method: PsiMethod, data: MethodData?): Boolean {
        val params = method.parameterList.parameters
        if (params.isEmpty()) return false
        if (params.size == 1) {
            val hintsProvider = InlayParamHints.getInstance()

            if (!hintsProvider.default.showIfMethodNameContainsParameterName.get()
                && isParamNameContainedInMethodName(params[0], data, method)) {
                return false
            }
        }
        return true
    }

    private fun isParamNameContainedInMethodName(parameter: PsiParameter, data: MethodData?, method: PsiMethod): Boolean {
        val parameterName = data?.getParameter(parameter.jvmIndex)?.name ?: parameter.name
        if (parameterName.length > 1) {
            return method.name.contains(parameterName, ignoreCase = true)
        }
        return false
    }

    private fun callInfo(callExpression: PsiCall, method: PsiMethod, data: MethodData?): CallInfo {
        val params = method.parameterList.parameters
        val hasVarArg = params.lastOrNull()?.isVarArgs ?: false
        val regularParamsCount = if (hasVarArg) params.size - 1 else params.size

        val arguments = callExpression.argumentList?.expressions ?: emptyArray()

        val regularArgInfos = params
            .take(regularParamsCount)
            .zip(arguments)
            .map { CallArgumentInfo(it.first, it.second, data) }

        val varargParam = if (hasVarArg) params.last() else null
        val varargExpressions = arguments.drop(regularParamsCount)
        return CallInfo(regularArgInfos, varargParam, varargExpressions, data)
    }

}

private fun List<Int>.areSequential(): Boolean {
    if (isEmpty()) throw IncorrectOperationException("List is empty")
    val ordered = (first() until first() + size).toList()
    if (ordered.size == size) {
        return zip(ordered).all { it.first == it.second }
    }
    return false
}


private fun inlayInfo(info: CallArgumentInfo, showOnlyIfExistedBefore: Boolean = false): InlayInfo {
    return inlayInfo(info.argument, info.parameter, info.data, showOnlyIfExistedBefore)
}


private fun inlayInfo(callArgument: PsiExpression, methodParam: PsiParameter, data: MethodData?, showOnlyIfExistedBefore: Boolean = false): InlayInfo {
    val paramName = data?.getParameter(methodParam.jvmIndex)?.name ?: methodParam.name
    val paramToShow = (if (methodParam.type is PsiEllipsisType) "..." else "") + paramName
    val offset = inlayOffset(callArgument)
    return InlayInfo(paramToShow, offset, showOnlyIfExistedBefore)
}

fun inlayOffset(callArgument: PsiExpression): Int = inlayOffset(callArgument, false)

fun inlayOffset(callArgument: PsiExpression, atEnd: Boolean): Int {
    if (callArgument.textRange.isEmpty) {
        val next = callArgument.nextSibling as? PsiWhiteSpace
        if (next != null) return next.textRange.endOffset
    }
    return if (atEnd) callArgument.textRange.endOffset else callArgument.textRange.startOffset
}

private val OPTIONAL_EMPTY: CallMatcher = CallMatcher.staticCall(CommonClassNames.JAVA_UTIL_OPTIONAL, "empty")
    .parameterCount(0)

private fun shouldShowHintsForExpression(callArgument: PsiElement): Boolean {
    if (InlayParamHints.getInstance().default.isShowHintWhenExpressionTypeIsClear.get()) return true
    return when (callArgument) {
        is PsiLiteralExpression -> true
        is PsiThisExpression -> true
        is PsiBinaryExpression -> true
        is PsiPolyadicExpression -> true
        is PsiPrefixExpression -> {
            val tokenType = callArgument.operationTokenType
            val isLiteral = callArgument.operand is PsiLiteralExpression
            isLiteral && (JavaTokenType.MINUS == tokenType || JavaTokenType.PLUS == tokenType)
        }
        is PsiMethodCallExpression -> OPTIONAL_EMPTY.matches(callArgument)
        else -> false
    }
}


private const val MIN_REASONABLE_PARAM_NAME_SIZE = 3

private class CallInfo(val regularArgs: List<CallArgumentInfo>, val varArg: PsiParameter?, val varArgExpressions: List<PsiExpression>, val data: MethodData?) {


    fun unclearInlays(substitutor: PsiSubstitutor): List<InlayInfo> {
        val inlays = mutableListOf<InlayInfo>()

        for (callInfo in regularArgs) {
            val inlay = when {
                shouldHideArgument(callInfo) -> null
                shouldShowHintsForExpression(callInfo.argument) -> inlayInfo(callInfo)
                !callInfo.isAssignable(substitutor) -> inlayInfo(callInfo, showOnlyIfExistedBefore = true)
                else -> null
            }

            inlay?.let { inlays.add(inlay) }
        }

        return inlays
    }


    fun sameTypeInlays(): List<InlayInfo> {
        val all = regularArgs.map { it.parameter.typeText() }
        val duplicated = all.toMutableList()

        all.distinct().forEach {
            duplicated.remove(it)
        }

        return regularArgs
            .filterNot { shouldHideArgument(it) }
            .filter { duplicated.contains(it.parameter.typeText()) && it.argument.text != it.parameter.name }
            .map { inlayInfo(it) }
    }

    private fun shouldHideArgument(callInfo: CallArgumentInfo) =
        isErroneousArg(callInfo) || isArgWithComment(callInfo) || argIfNamedHasSameNameAsParameter(callInfo)

    private fun isErroneousArg(arg : CallArgumentInfo): Boolean {
        return arg.argument is PsiEmptyExpressionImpl || arg.argument.prevSibling is PsiEmptyExpressionImpl
    }

    private fun isArgWithComment(arg : CallArgumentInfo): Boolean {
        return hasComment(arg.argument, PsiElement::getNextSibling) || hasComment(arg.argument, PsiElement::getPrevSibling)
    }

    private fun argIfNamedHasSameNameAsParameter(arg : CallArgumentInfo): Boolean {
        val argName = when (val argExpr = arg.argument) {
            is PsiReferenceExpression -> argExpr.referenceName
            is PsiMethodCallExpression -> argExpr.methodExpression.referenceName
            else -> null
        }?.lowercase(Locale.getDefault()) ?: return false
        val paramName = arg.parameter.name.lowercase(Locale.getDefault())
        if (paramName.length < MIN_REASONABLE_PARAM_NAME_SIZE || argName.length < MIN_REASONABLE_PARAM_NAME_SIZE) {
            return false
        }
        return argName.contains(paramName) || paramName.contains(argName)
    }

    private fun hasComment(e: PsiElement, next: (PsiElement) -> PsiElement?) : Boolean {
        var current = next(e)
        while (current != null) {
            if (current is PsiComment) {
                return true
            }
            if (current !is PsiWhiteSpace) {
                break
            }
            current = next(current)
        }
        return false
    }

    fun varargsInlay(substitutor: PsiSubstitutor, data: MethodData?): InlayInfo? {
        if (varArg == null) return null

        var hasNonassignable = false
        for (expr in varArgExpressions) {
            if (shouldShowHintsForExpression(expr)) {
                return inlayInfo(varArgExpressions.first(), varArg, data)
            }
            hasNonassignable = hasNonassignable || !varArg.isAssignable(expr, substitutor)
        }

        return if (hasNonassignable) inlayInfo(varArgExpressions.first(), varArg, data, showOnlyIfExistedBefore = true) else null
    }

}


private class CallArgumentInfo(val parameter: PsiParameter, val argument: PsiExpression, val data: MethodData?) {
    fun isAssignable(substitutor: PsiSubstitutor): Boolean {
        return parameter.isAssignable(argument, substitutor)
    }
}


private fun PsiParameter.isAssignable(argument: PsiExpression, substitutor: PsiSubstitutor = PsiSubstitutor.EMPTY): Boolean {
    val substitutedType = substitutor.substitute(type) ?: return false
    if (PsiPolyExpressionUtil.isPolyExpression(argument)) return true
    return argument.type?.isAssignableTo(substitutedType) ?: false
}


private fun PsiType.isAssignableTo(parameterType: PsiType): Boolean {
    return TypeConversionUtil.isAssignable(parameterType, this)
}


private fun PsiParameter.typeText() = type.canonicalText