package dev.sandipchitale.jbdependencygitrepo;

import com.intellij.ide.projectView.impl.nodes.BasePsiNode;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import com.intellij.ui.components.JBScrollPane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OpenDependencyGitRepoAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent actionEvent) {
        Project project = actionEvent.getProject();
        final Navigatable[] navigatables = actionEvent.getData(CommonDataKeys.NAVIGATABLE_ARRAY);
        if (navigatables != null && navigatables.length > 0) {
            Navigatable navigatable = navigatables[0];
            if (navigatable instanceof BasePsiNode basePsiNode) {
                VirtualFile virtualFile = basePsiNode.getVirtualFile();
                ArtifactInfo artifactInfo = parsePath(Objects.requireNonNull(virtualFile).getPath());
                new DependencyInfoDialog(project, artifactInfo.GAV() + "\n\n" + artifactInfo.javaPath()).show();
            }
        }
    }

    @Override
    public void update(@NotNull AnActionEvent actionEvent) {
        boolean isEnabled = false;
        final Navigatable[] navigatables = actionEvent.getData(CommonDataKeys.NAVIGATABLE_ARRAY);
        if (navigatables != null && navigatables.length > 0) {
            Navigatable navigatable = navigatables[0];
            if (navigatable instanceof BasePsiNode basePsiNode) {
                isEnabled = true;
            }
        }
        actionEvent.getPresentation().setEnabledAndVisible(isEnabled);

    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    /**
     * Holds the parsed GAV + ClassPath information.
     */
    record ArtifactInfo(String groupId, String artifactId, String version, String classPath) {
        /**
         * Converts the .class path to a .java path.
         */
        public String javaPath() {
            return classPath.replace(".class", ".java");
        }

        public String GAV() {
            return  groupId + ":" + artifactId + ":" + version;
        }

        /**
         * Extracts just the .java filename.
         */
        public String javaFilename() {
            return javaPath().substring(javaPath().lastIndexOf('/') + 1);
        }

        /**
         * Extracts just the package path, e.g., "org/springframework/boot".
         */
        public String packagePath() {
            int lastSlash = javaPath().lastIndexOf('/');
            if (lastSlash == -1) {
                return ""; // Default package
            }
            return javaPath().substring(0, lastSlash);
        }
    }


    // Regex for Gradle: .../caches/modules-2/files-2.1/{groupId}/{artifactId}/{version}/{hash}/{artifactId}-{version}.jar!/
    private static final Pattern GRADLE_PATTERN_WITHOUT_PATH = Pattern.compile(
            ".*/files-2\\.1/([^/]+)/([^/]+)/([^/]+)/[^/]+/[^/]+\\.jar!/");

    private static final Pattern GRADLE_PATTERN_WITH_PATH = Pattern.compile(
            ".*/files-2\\.1/([^/]+)/([^/]+)/([^/]+)/[^/]+/[^/]+\\.jar!/([^!]+)");

    // Regex for Maven: .../.m2/repository/{groupId/path}/{artifactId}/{version}/{artifactId}-{version}.jar!/
    private static final Pattern MAVEN_PATTERN_WITHOUT_PATH = Pattern.compile(
            ".*/\\.m2/repository/((?:[^/]+/)+)([^/]+)/([^/]+)/[^/]+\\.jar!/");

    // Regex for Maven: .../.m2/repository/{groupId/path}/{artifactId}/{version}/{artifactId}-{version}.jar!/{path/to/classfile.class}
    private static final Pattern MAVEN_PATTERN_WITH_PATH = Pattern.compile(
            ".*/\\.m2/repository/((?:[^/]+/)+)([^/]+)/([^/]+)/[^/]+\\.jar!/([^!]+)");

    /**
     * Parses the input path string to find the GAV and class path.
     */
    public static ArtifactInfo parsePath(String path) {
        Matcher gradleMatcher = GRADLE_PATTERN_WITHOUT_PATH.matcher(path);
        if (gradleMatcher.matches()) {
            String groupId = gradleMatcher.group(1);
            String artifactId = gradleMatcher.group(2);
            String version = gradleMatcher.group(3);
            return new ArtifactInfo(groupId, artifactId, version, "");
        }

        Matcher gradleMatcherWithPath = GRADLE_PATTERN_WITH_PATH.matcher(path);
        if (gradleMatcherWithPath.matches()) {
            String groupId = gradleMatcherWithPath.group(1);
            String artifactId = gradleMatcherWithPath.group(2);
            String version = gradleMatcherWithPath.group(3);
            String classPath = gradleMatcherWithPath.group(4);
            return new ArtifactInfo(groupId, artifactId, version, classPath);
        }

        Matcher mavenMatcher = MAVEN_PATTERN_WITHOUT_PATH.matcher(path);
        if (mavenMatcher.matches()) {
            String groupIdPath = mavenMatcher.group(1);
            String groupId = groupIdPath.replace('/', '.').replaceAll(".$", ""); // remove trailing dot
            String artifactId = mavenMatcher.group(2);
            String version = mavenMatcher.group(3);
            return new ArtifactInfo(groupId, artifactId, version, "");
        }

        Matcher mavenMatcherWithPath = MAVEN_PATTERN_WITH_PATH.matcher(path);
        if (mavenMatcherWithPath.matches()) {
            String groupIdPath = mavenMatcherWithPath.group(1);
            String groupId = groupIdPath.replace('/', '.').replaceAll(".$", ""); // remove trailing dot
            String artifactId = mavenMatcherWithPath.group(2);
            String version = mavenMatcherWithPath.group(3);
            String classPath = mavenMatcherWithPath.group(4);
            return new ArtifactInfo(groupId, artifactId, version, classPath);
        }

        throw new IllegalArgumentException("Path does not match known Maven or Gradle cache structure.");
    }

    static class DependencyInfoDialog extends DialogWrapper {
        private final String detail;

        public DependencyInfoDialog(Project project, String detail) {
            super(project);
            this.detail = detail;
            setTitle("Dependency Info");

            init();
        }

        @Nullable
        @Override
        protected JComponent createCenterPanel() {
            JTextArea textArea = new JTextArea(detail);
            textArea.setEditable(false);
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);
            JBScrollPane scrollPane = new JBScrollPane(textArea);
            scrollPane.setPreferredSize(new Dimension(700, 150));
            return scrollPane;
        }

        @Override
        protected Action @NotNull [] createActions() {
            // Only show OK button
            Action okAction = getOKAction();
            return new Action[]{okAction};
        }
    }
}