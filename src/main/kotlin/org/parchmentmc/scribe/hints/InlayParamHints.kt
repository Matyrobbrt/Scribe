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
import com.intellij.codeInsight.hints.*
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.*
import org.parchmentmc.scribe.ParchmentMappings
import org.parchmentmc.scribe.util.jvmIndex

class InlayParamHints : InlayParameterHintsProvider {
    val default = JavaInlayParameterHintsProvider()

    companion object {
        fun getInstance(): InlayParamHints =
            InlayParameterHintsExtension.forLanguage(JavaLanguage.INSTANCE) as InlayParamHints
    }

    override fun getDefaultBlackList(): Set<String> = default.defaultBlackList

    override fun getHintInfo(element: PsiElement): HintInfo? {
        if (element is PsiCallExpression && element !is PsiEnumConstant) {
            val resolvedElement =
                (if (JavaMethodCallElement.isCompletionMode(element)) CompletionMemory.getChosenMethod(element) else null)
                    ?: element.resolveMethodGenerics().element
            if (resolvedElement is PsiMethod) {
                return getMethodInfo(resolvedElement)
            }
        }
        return null
    }

    override fun getParameterHints(element: PsiElement): List<InlayInfo> {
        if (element is PsiCall) {
            if (element is PsiEnumConstant && !default.isShowHintsForEnumConstants.get()) return emptyList()
            if (element is PsiNewExpression && !default.isShowHintsForNewExpressions.get()) return emptyList()
            return org.parchmentmc.scribe.hints.JavaInlayHintsProvider.hints(element).toList()
        }
        return emptyList()
    }

    override fun canShowHintsWhenDisabled(): Boolean {
        return true;
    }

    private fun getMethodInfo(method: PsiMethod): HintInfo.MethodInfo? {
        val containingClass = method.containingClass ?: return null
        val parchment = ParchmentMappings.getInstance(method.project).getMethodData(method, false, true)
        val fullMethodName = StringUtil.getQualifiedName(containingClass.qualifiedName, method.name)

        val paramNames: List<String> = method.parameterList.parameters.map {
            parchment?.getParameter(it.jvmIndex)?.name ?: it.name
        }
        return HintInfo.MethodInfo(fullMethodName, paramNames)
    }
}