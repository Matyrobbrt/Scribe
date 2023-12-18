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

import com.intellij.codeInsight.javadoc.JavaDocInfoGeneratorFactory;
import com.intellij.model.Pointer;
import com.intellij.navigation.TargetPresentation;
import com.intellij.openapi.project.DumbService;
import com.intellij.platform.backend.documentation.DocumentationResult;
import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.parchmentmc.scribe.ParchmentMappings;
import org.parchmentmc.scribe.util.Psi_utilsKt;

public class ParchmentDocs implements PsiDocumentationTargetProvider {
    @Override
    public @Nullable DocumentationTarget documentationTarget(@NotNull PsiElement element, @Nullable PsiElement originalElement) {
        if (DumbService.isDumb(element.getProject())) return null; // Dumb means we can't resolve stuff

        if (element instanceof PsiMethod method) {
            final var qualified = method.getContainingClass().getQualifiedName();
            final var md = ParchmentMappings.Companion.getInstance(element.getProject())
                    .getMethodData(method, false, qualified.startsWith("net.minecraft") || qualified.startsWith("com.mojang"));
            if (md == null) return null;

            return new ElementTarget(Psi_utilsKt.copyFromParchment(method, md, true));
        } else if (element instanceof PsiClass cls) {
            final var cs = ParchmentMappings.Companion.getInstance(element.getProject())
                    .getClassData(cls, false);
            if (cs == null) return null;

            return new ElementTarget(Psi_utilsKt.copyFromParchment(cls, cs));
        } else if (element instanceof PsiField field) {
            final var cs = ParchmentMappings.Companion.getInstance(element.getProject())
                    .getFieldData(field, false);
            if (cs == null) return null;

            return new ElementTarget(Psi_utilsKt.copyFromParchment(field, cs));
        }
        return null;
    }

    public record ElementTarget(PsiElement element) implements DocumentationTarget {
        @NotNull
        @Override
        public Pointer<? extends DocumentationTarget> createPointer() {
            return Pointer.hardPointer(this);
        }

        @NotNull
        @Override
        public TargetPresentation computePresentation() {
            return TargetPresentation.builder(computeDocumentationHint()).presentation();
        }

        @Override
        public String computeDocumentationHint() {
            final var factory = JavaDocInfoGeneratorFactory.getBuilder(element.getProject()).setPsiElement(element).create();
            return factory.generateDocInfo(null);
        }

        @Override
        public DocumentationResult computeDocumentation() {
            var doc = computeDocumentationHint();
            return doc == null ? null : DocumentationResult.documentation(doc);
        }
    }
}
