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

import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.codeInsight.completion.CompletionMemory;
import com.intellij.codeInsight.completion.JavaMethodCallElement;
import com.intellij.codeInsight.hint.ParameterInfoControllerBase;
import com.intellij.codeInsight.hint.api.impls.MethodParameterInfoHandler;
import com.intellij.codeInsight.hints.ParameterHintsPass;
import com.intellij.lang.LanguageExtensionPoint;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.lang.parameterInfo.CreateParameterInfoContext;
import com.intellij.lang.parameterInfo.LanguageParameterInfo;
import com.intellij.lang.parameterInfo.ParameterInfoHandler;
import com.intellij.lang.parameterInfo.ParameterInfoHandlerWithTabActionSupport;
import com.intellij.lang.parameterInfo.ParameterInfoUIContext;
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext;
import com.intellij.openapi.extensions.ExtensionPointListener;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.PsiCall;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.impl.light.LightParameter;
import com.intellij.psi.impl.light.LightParameterListBuilder;
import com.intellij.psi.infos.CandidateInfo;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.parchmentmc.scribe.ParchmentMappings;
import org.parchmentmc.scribe.util.Desc_index_utilsKt;

import java.util.Set;
import java.util.stream.Stream;

public class ParchmentParamInfoHandler implements ParameterInfoHandlerWithTabActionSupport<PsiExpressionList, Object, PsiExpression>, DumbAware {
    private final MethodParameterInfoHandler delegate = new MethodParameterInfoHandler();
    private static boolean done, wasYeeted;
    public ParchmentParamInfoHandler() {
        final var ep = ExtensionPointName.create("com.intellij.codeInsight.parameterInfo");

        if (!done) {
            done = true;
            ep.addExtensionPointListener((ExtensionPointListener) new Listener(this));
        }
    }

    @Override
    public PsiExpression @NotNull [] getActualParameters(@NotNull PsiExpressionList o) {
        yeet();
        return new PsiExpression[0];
    }

    @Override
    public @NotNull IElementType getActualParameterDelimiterType() {
        yeet();
        return delegate.getActualParameterDelimiterType();
    }

    @Override
    public @NotNull IElementType getActualParametersRBraceType() {
        yeet();
        return delegate.getActualParametersRBraceType();
    }

    @Override
    public @NotNull Set<Class<?>> getArgumentListAllowedParentClasses() {
        yeet();
        return delegate.getArgumentListAllowedParentClasses();
    }

    @Override
    public @NotNull Set<? extends Class<?>> getArgListStopSearchClasses() {
        yeet();
        return delegate.getArgListStopSearchClasses();
    }

    @Override
    public @NotNull Class<PsiExpressionList> getArgumentListClass() {
        yeet();
        return delegate.getArgumentListClass();
    }

    @Override
    public @Nullable PsiExpressionList findElementForParameterInfo(@NotNull CreateParameterInfoContext context) {
        yeet();
        return delegate.findElementForParameterInfo(context);
    }

    @Override
    public void showParameterInfo(@NotNull PsiExpressionList element, @NotNull CreateParameterInfoContext context) {
        yeet();
        int offset = element.getTextRange().getStartOffset();
        if (CodeInsightSettings.getInstance().SHOW_PARAMETER_NAME_HINTS_ON_COMPLETION) {
            ParameterInfoControllerBase controller = ParameterInfoControllerBase.findControllerAtOffset(context.getEditor(), offset);
            PsiElement parent = element.getParent();
            if (parent instanceof PsiCall && controller != null && controller.isHintShown(false)) {
                Object highlighted = controller.getHighlighted();
                Object[] objects = controller.getObjects();
                if (objects != null && objects.length > 0 && (highlighted != null || objects.length == 1)) {
                    PsiCall methodCall = (PsiCall)parent;
                    JavaMethodCallElement.setCompletionModeIfNotSet(methodCall, controller);
                    PsiMethod targetMethod = (PsiMethod)((CandidateInfo)(highlighted == null ? objects[0] : highlighted)).getElement();
                    CompletionMemory.registerChosenMethod(targetMethod, methodCall);
                    controller.setPreservedOnHintHidden(true);
                    ParameterHintsPass.asyncUpdate(methodCall, context.getEditor());
                }
            }
        }
        context.showHint(element, offset, this);
    }

    @Override
    public @Nullable PsiExpressionList findElementForUpdatingParameterInfo(@NotNull UpdateParameterInfoContext context) {
        yeet();
        return delegate.findElementForUpdatingParameterInfo(context);
    }

    @Override
    public void updateParameterInfo(@NotNull PsiExpressionList psiExpressionList, @NotNull UpdateParameterInfoContext context) {
        yeet();
        for (int i = 0; i < context.getObjectsToView().length; i++) {
            final var info = (CandidateInfo) context.getObjectsToView()[i];
            final var method = (PsiMethod) info.getElement();
            final var md = ParchmentMappings.Companion.getInstance(method.getProject())
                    .getMethodData(method, false, true);
            if (md == null) {
                continue;
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

            var newMethod = new LightMethodBuilder(
                    method.getManager(),
                    method.getLanguage(),
                    method.getName(),
                    params,
                    method.getModifierList(),
                    method.getThrowsList(),
                    method.getTypeParameterList()
            ).setContainingClass(method.getContainingClass());
            context.getObjectsToView()[i] = new CandidateInfo(
                    newMethod, info.getSubstitutor(),
                    !info.isAccessible(), !info.isStaticsScopeCorrect(),
                    info.getCurrentFileResolveScope()
            );
        }
        delegate.updateParameterInfo(psiExpressionList, context);
    }

    @Override
    public void updateUI(Object p, @NotNull ParameterInfoUIContext context) {
        yeet();
        if (DumbService.isDumb(context.getParameterOwner().getProject())) {
            delegate.updateUI(p, context);
            return;
        }

        if (p instanceof CandidateInfo info) {
            final var method = (PsiMethod) info.getElement();
            final var md = ParchmentMappings.Companion.getInstance(method.getProject())
                    .getMethodData(method, false, true);
            if (md == null) {
                delegate.updateUI(p, context);
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

            var newMethod = new LightMethodBuilder(
                    method.getManager(),
                    method.getLanguage(),
                    method.getName(),
                    params,
                    method.getModifierList(),
                    method.getThrowsList(),
                    method.getTypeParameterList()
            ).setContainingClass(method.getContainingClass());

            delegate.updateUI(new CandidateInfo(
                    newMethod, info.getSubstitutor(),
                    !info.isAccessible(), !info.isStaticsScopeCorrect(),
                    info.getCurrentFileResolveScope()
            ), context);
        } else if (p instanceof PsiMethod method) {
            final var md = ParchmentMappings.Companion.getInstance(method.getProject())
                    .getMethodData(method, false, true);
            if (md == null) {
                delegate.updateUI(p, context);
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

            var newMethod = new LightMethodBuilder(
                    method.getManager(),
                    method.getLanguage(),
                    method.getName(),
                    params,
                    method.getModifierList(),
                    method.getThrowsList(),
                    method.getTypeParameterList()
            ).setContainingClass(method.getContainingClass());

            delegate.updateUI(newMethod, context);
        }
        delegate.updateUI(p, context);
    }

    private void yeet() {
        if (wasYeeted) return;

        final var ep = ExtensionPointName.create("com.intellij.codeInsight.parameterInfo");
        final var found = Stream.of(ep.getPointImpl(null).getExtensions()).filter(o -> ((LanguageExtensionPoint) o).implementationClass.equals("com.intellij.codeInsight.hint.api.impls.MethodParameterInfoHandler")).findFirst();
        found.ifPresent(o -> ep.getPointImpl(null).unregisterExtension(o));
        if (found.isEmpty()) {
            return;
        }
        wasYeeted = true;

        LanguageParameterInfo.INSTANCE.allForLanguage(JavaLanguage.INSTANCE)
                .stream().filter(p -> p instanceof MethodParameterInfoHandler)
                .findFirst().ifPresent(f -> LanguageParameterInfo.INSTANCE.removeExplicitExtension(JavaLanguage.INSTANCE, f));
        LanguageParameterInfo.INSTANCE.addExplicitExtension(JavaLanguage.INSTANCE, this);
    }

    public static final class Listener implements ExtensionPointListener<ParameterInfoHandler<?, ?>> {
        public Listener(ParchmentParamInfoHandler thz) {
            thz.yeet();
        }
        @Override
        public void extensionAdded(ParameterInfoHandler extension, @NotNull PluginDescriptor pluginDescriptor) {
            if (extension instanceof MethodParameterInfoHandler) {
                ExtensionPointName.create("com.intellij.codeInsight.parameterInfo").getPointImpl(null).unregisterExtension(extension);
                LanguageParameterInfo.INSTANCE.removeExplicitExtension(JavaLanguage.INSTANCE, extension);
            }
        }
    }
}
