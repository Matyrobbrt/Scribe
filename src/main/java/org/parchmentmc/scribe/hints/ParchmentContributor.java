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

package org.parchmentmc.scribe.hints;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.JavaMethodCallElement;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.impl.light.LightParameter;
import com.intellij.psi.impl.light.LightParameterListBuilder;
import org.jetbrains.annotations.NotNull;
import org.parchmentmc.scribe.ParchmentMappings;
import org.parchmentmc.scribe.util.Desc_index_utilsKt;

public class ParchmentContributor extends CompletionContributor {
    @Override
    public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        result.runRemainingContributors(parameters, c -> {
            final var element = c.getLookupElement();
            final var psi = element.getPsiElement();
            if (psi instanceof PsiMethod method) {
                final var md = ParchmentMappings.Companion.getInstance(method.getProject())
                        .getMethodData(method, false, true);
                if (md == null) {
                    result.addElement(element);
                    return;
                }

                var params = new LightParameterListBuilder(method.getManager(), method.getLanguage());
                for (PsiParameter parameter : method.getParameterList().getParameters()) {
                    final var pd = md.getParameter(Desc_index_utilsKt.getJvmIndex(parameter));
                    if (pd == null || pd.getName() == null) {
                        params.addParameter(parameter);
                    } else {
                        params.addParameter(new LightParameter(pd.getName(), parameter.getType(), method));
                    }
                }

                var delegate = new JavaMethodCallElement(new LightMethodBuilder(
                        method.getManager(),
                        method.getLanguage(),
                        method.getName(),
                        params,
                        method.getModifierList(),
                        method.getThrowsList(),
                        method.getTypeParameterList()
                ).setContainingClass(method.getContainingClass()));

                if (element instanceof PrioritizedLookupElement<?> p) {
                    result.addElement(PrioritizedLookupElement.withPriority(delegate, p.getPriority()));
                } else {
                    result.addElement(delegate);
                }
            } else {
                result.addElement(element);
            }
        });
    }
}
