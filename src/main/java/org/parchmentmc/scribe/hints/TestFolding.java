package org.parchmentmc.scribe.hints;

import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.CustomFoldingBuilder;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.lang.jvm.JvmModifier;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReferenceList;
import com.intellij.psi.PsiReferenceParameterList;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiSuperExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.parchmentmc.scribe.ParchmentMappings;
import org.parchmentmc.scribe.util.Desc_index_utilsKt;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class TestFolding extends CustomFoldingBuilder implements DumbAware {

    @Override
    protected void buildLanguageFoldRegions(@NotNull List<FoldingDescriptor> descriptors, @NotNull PsiElement root, @NotNull Document document, boolean quick) {
        if (!(root instanceof PsiJavaFile)) return;
        PsiJavaFile file = (PsiJavaFile) root;

        PsiClass[] classes = file.getClasses();
        final Consumer<PsiClass> cons = new Consumer<>() {
            @Override
            public void accept(PsiClass aClass) {
                if (ParchmentMappings.Companion.getInstance(file.getProject())
                        .getClassData(aClass, false) == null) return;

                for (PsiMethod method : aClass.getMethods()) {
                    final var mt = ParchmentMappings.Companion.getInstance(file.getProject())
                            .getMethodData(method, false, true);
                    if (mt == null) continue;
                    final Map<String, String> parameterMapping = new HashMap<>();

                    method.accept(new JavaRecursiveElementWalkingVisitor() {
                        @Override
                        public void visitClass(@NotNull PsiClass aClass) {
                            aClass.acceptChildren(this);
                        }

                        @Override
                        public void visitAnonymousClass(@NotNull PsiAnonymousClass aClass) {
                            aClass.acceptChildren(this);
                        }

                        @Override
                        public void visitReturnStatement(@NotNull PsiReturnStatement statement) {
                            statement.acceptChildren(this);
                        }

                        @Override
                        public void visitReferenceParameterList(@NotNull PsiReferenceParameterList list) {
                            for (PsiElement child : list.getChildren()) {
                                child.accept(this);
                            }
                        }

                        @Override
                        public void visitMethod(@NotNull PsiMethod method) {
                            final var mt = ParchmentMappings.Companion.getInstance(file.getProject())
                                    .getMethodData(method, false, true);
                            if (mt != null) {
                                mt.getParameters()
                                        .forEach(par -> parameterMapping.put(Desc_index_utilsKt.getParameterByJvmIndex(method, par.getIndex()).getName(), par.getName()));
                            }
                            method.acceptChildren(this);
                        }

                        @Override
                        public void visitLambdaExpression(@NotNull PsiLambdaExpression expression) {
                            final var lambda = ParchmentMappings.Companion.getInstance(file.getProject()).getMethodData(expression, false);
                            if (lambda != null) {
                                for (PsiParameter parameter : expression.getParameterList().getParameters()) {
                                    final var mapped = lambda.getParameter(Desc_index_utilsKt.getJvmIndex(parameter));
                                    if (mapped != null) {
                                        parameterMapping.put(parameter.getName(), mapped.getName());
                                        addToFold(
                                                descriptors,
                                                parameter,
                                                document,
                                                true,
                                                mapped.getName(),
                                                parameter.getNameIdentifier().getTextRange(),
                                                true
                                        );
                                    }
                                }
                            }

                            if (expression.getBody() != null) {
                                expression.getBody().accept(this);
                            }
                        }

                        @Override
                        public void visitParameter(@NotNull PsiParameter parameter) {
                            var mapping = parameterMapping.get(parameter.getName());
                            if (mapping != null) {
                                addToFold(
                                        descriptors,
                                        parameter,
                                        document,
                                        true,
                                        mapping,
                                        parameter.getNameIdentifier().getTextRange(),
                                        true
                                );
                            }
                        }

                        @Override
                        public void visitSuperExpression(@NotNull PsiSuperExpression expression) {
                            super.visitSuperExpression(expression);
                        }

                        private PsiElement firstChild(PsiElement other) {
                            while (other.getFirstChild() != null && !(other.getFirstChild() instanceof PsiReferenceParameterList)) {
                                other = other.getFirstChild();
                            }
                            return other;
                        }

                        @Override
                        public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                            PsiElement owner = expression.getMethodExpression().getQualifierExpression();
                            if (owner != null) {
                                owner = firstChild(owner);
                                var mapped = parameterMapping.get(owner.getText());
                                if (mapped != null) {
                                    addToFold(
                                            descriptors,
                                            expression,
                                            document,
                                            true,
                                            mapped,
                                            owner.getTextRange(),
                                            true
                                    );
                                }
                            }

                            for (PsiElement child : expression.getChildren()) {
                                child.accept(this);
                            }

                            for (PsiExpression psiExpression : expression.getArgumentList().getExpressions()) {
                                psiExpression.accept(this);
                            }
                        }

                        @Override
                        public void visitReferenceList(@NotNull PsiReferenceList list) {
                            for (PsiElement child : list.getChildren()) {
                                child.accept(this);
                            }
                        }

                        @Override
                        public void visitReferenceExpression(@NotNull PsiReferenceExpression expression) {
                            var mapped = parameterMapping.get(expression.getReferenceName());
                            if (mapped != null) {
                                addToFold(
                                        descriptors,
                                        expression,
                                        document,
                                        true,
                                        mapped,
                                        expression.getAbsoluteRange(),
                                        true
                                );
                            }

                            for (PsiElement child : expression.getChildren()) {
                                child.accept(this);
                            }
                        }
                    });
                }

                for (PsiClass innerClass : aClass.getInnerClasses()) {
                    accept(innerClass);
                }
            }
        };
        for (PsiClass aClass : classes) {
            cons.accept(aClass);
        }
    }

    private static void addToFold(@NotNull List<? super FoldingDescriptor> list,
                                  @NotNull PsiElement elementToFold,
                                  @NotNull Document document,
                                  boolean allowOneLiners,
                                  @NotNull String placeholder,
                                  @Nullable TextRange range,
                                  boolean isCollapsedByDefault) {
        if (range != null) {
            addFoldRegion(list, elementToFold, document, allowOneLiners, range, placeholder, isCollapsedByDefault);
        }
    }

    private static void addFoldRegion(@NotNull List<? super FoldingDescriptor> list,
                                      @NotNull PsiElement elementToFold,
                                      @NotNull Document document,
                                      boolean allowOneLiners,
                                      @NotNull TextRange range, @NotNull String placeholder, boolean isCollapsedByDefault) {
        final TextRange fileRange = elementToFold.getContainingFile().getTextRange();
        if (range.equals(fileRange)) return;

        if (!allowOneLiners) {
            int startLine = document.getLineNumber(range.getStartOffset());
            int endLine = document.getLineNumber(range.getEndOffset() - 1);
            if (startLine >= endLine || range.getLength() <= 1) {
                return;
            }
        }
        list.add(new FoldingDescriptor(elementToFold.getNode(), range, null, Collections.emptySet(), true, placeholder, isCollapsedByDefault));
    }

    @Override
    protected String getLanguagePlaceholderText(@NotNull ASTNode node, @NotNull TextRange range) {
        return null;
    }

    @Override
    protected boolean isRegionCollapsedByDefault(@NotNull ASTNode node) {
        return true;
    }
}
