package com.salesforce.bazel.eclipse.core.model.discovery;

import static java.lang.String.format;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static org.eclipse.core.runtime.SubMonitor.SUPPRESS_ALL_LABELS;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.idea.blaze.base.ideinfo.TargetKey;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.model.primitives.LanguageClass;
import com.salesforce.bazel.eclipse.core.classpath.BazelClasspathScope;
import com.salesforce.bazel.eclipse.core.classpath.CompileAndRuntimeClasspath;
import com.salesforce.bazel.eclipse.core.model.BazelPackage;
import com.salesforce.bazel.eclipse.core.model.BazelProject;
import com.salesforce.bazel.eclipse.core.model.BazelTarget;
import com.salesforce.bazel.eclipse.core.model.BazelWorkspace;
import com.salesforce.bazel.eclipse.core.model.BazelWorkspaceBlazeInfo;
import com.salesforce.bazel.eclipse.core.model.discovery.projects.JavaProjectInfo;
import com.salesforce.bazel.eclipse.core.util.trace.TracingSubMonitor;
import com.salesforce.bazel.sdk.aspects.intellij.IntellijAspects;
import com.salesforce.bazel.sdk.aspects.intellij.IntellijAspects.OutputGroup;
import com.salesforce.bazel.sdk.command.BazelBuildWithIntelliJAspectsCommand;
import com.salesforce.bazel.sdk.command.querylight.BazelRuleAttribute;
import com.salesforce.bazel.sdk.model.BazelLabel;

/**
 * Default implementation of {@link TargetProvisioningStrategy} which provisions a single project per supported target.
 * <p>
 * <ul>
 * <li>One Eclipse project is created per supported <code>java_*</code> target per package.</li>
 * <li>The build path is setup specifically for that target, allowing for best support in the IDE.</li>
 * <li>Projects are created in a project area (<code>.eclipse/projects</code> folder inside the workspace) and files are
 * created as links. This makes SCM not really functional for these.</li>
 * <li>Targets inside the root (empty) package <code>//:*</code> are supported.</li>
 * </ul>
 * </p>
 *
 * @since 2.0
 */
public class ProjectPerTargetProvisioningStrategy extends BaseProvisioningStrategy {

    private static Logger LOG = LoggerFactory.getLogger(ProjectPerTargetProvisioningStrategy.class);

    public static final String STRATEGY_NAME = "project-per-target";

    private static final String PROJECT_NAME_FORMAT = "project_name_format";

    /**
     * Helper method to add a BazelPackage for a given label to the collection. Safely handles external packages and
     * packages that may not exist in the workspace.
     */
    private void addPackageForLabel(Label label, BazelWorkspace workspace, Set<BazelPackage> packages) {
        if (label.isExternal()) {
            return;
        }
        try {
            var bazelPackage = workspace.getBazelPackage(new BazelLabel(label));
            if (bazelPackage.exists()) {
                packages.add(bazelPackage);
            }
        } catch (IllegalArgumentException e) {
            LOG.trace("Skipping label not rooted at workspace: {}", label);
        }
    }

    /**
     * Collects all BazelPackages that are referenced by the dependencies of the given projects. This is used for
     * performance optimization - by pre-loading these packages in batch, we avoid repeated individual Bazel query
     * commands during classpath resolution.
     *
     * @param bazelProjects
     *            the projects whose dependencies should be analyzed
     * @param aspectsInfo
     *            the aspects information containing dependency data
     * @param workspace
     *            the Bazel workspace
     * @return set of BazelPackages to pre-load
     * @throws CoreException
     */
    private void collectDependencyPackagesForTarget(BazelTarget target, JavaAspectsInfo aspectsInfo,
            BazelWorkspace workspace, Set<BazelPackage> packages) {
        var targetKey = TargetKey.forPlainTarget(target.getLabel().toPrimitive());
        var targetInfo = aspectsInfo.get(targetKey);

        if (targetInfo != null) {
            for (var dep : targetInfo.getDependencies()) {
                addPackageForLabel(dep.getTargetKey().getLabel(), workspace, packages);
            }

            var runtimeClasspath = aspectsInfo.getRuntimeClasspath(targetKey);
            if (runtimeClasspath != null) {
                for (var jar : runtimeClasspath) {
                    if (jar.targetKey != null) {
                        addPackageForLabel(jar.targetKey.getLabel(), workspace, packages);
                    }
                }
            }
        }
    }

    private Set<BazelPackage> collectDependencyPackages(Collection<BazelProject> bazelProjects,
            JavaAspectsInfo aspectsInfo, BazelWorkspace workspace) throws CoreException {
        Set<BazelPackage> packages = new HashSet<>();

        for (BazelProject bazelProject : bazelProjects) {
            if (!bazelProject.isTargetProject()) {
                continue;
            }

            // Collect deps from the primary target
            collectDependencyPackagesForTarget(bazelProject.getBazelTarget(), aspectsInfo, workspace, packages);

            // Also collect deps from unprovisioned sibling targets in the same package
            for (BazelTarget sibling : getUnprovisionedSiblingTargets(bazelProject)) {
                collectDependencyPackagesForTarget(sibling, aspectsInfo, workspace, packages);
            }
        }

        return packages;
    }

    @Override
    public Map<BazelProject, CompileAndRuntimeClasspath> computeClasspaths(Collection<BazelProject> bazelProjects,
            BazelWorkspace workspace, BazelClasspathScope scope, IProgressMonitor progress) throws CoreException {
        LOG.debug("Computing classpath for projects: {}", bazelProjects);
        try {
            var monitor = SubMonitor.convert(progress, "Computing Bazel project classpaths", 1 + bazelProjects.size());

            // Build targets list including unprovisioned sibling targets
            List<BazelLabel> targetsToBuild = new ArrayList<>(bazelProjects.size());
            Set<BazelLabel> addedLabels = new HashSet<>();
            Map<BazelProject, List<BazelTarget>> siblingTargetsMap = new HashMap<>();

            for (BazelProject bazelProject : bazelProjects) {
                monitor.checkCanceled();

                if (!bazelProject.isTargetProject()) {
                    LOG.warn("Skipping non-target project '{}' in classpath computation", bazelProject.getName());
                    continue;
                }

                // Add the primary target
                var primaryLabel = bazelProject.getBazelTarget().getLabel();
                if (addedLabels.add(primaryLabel)) {
                    targetsToBuild.add(primaryLabel);
                }

                // Discover and add unprovisioned sibling targets from the same package
                var siblings = getUnprovisionedSiblingTargets(bazelProject);
                siblingTargetsMap.put(bazelProject, siblings);
                for (BazelTarget sibling : siblings) {
                    if (addedLabels.add(sibling.getLabel())) {
                        targetsToBuild.add(sibling.getLabel());
                    }
                }
            }

            if (targetsToBuild.isEmpty()) {
                return Map.of();
            }

            var workspaceRoot = workspace.getLocation().toPath();

            var availableDependencies = queryForDepsWithClasspathDepth(workspace, targetsToBuild);

            // run the aspect to compute all required information
            var aspects = workspace.getParent().getModelManager().getIntellijAspects();
            var languages = Set.of(LanguageClass.JAVA);
            var onlyDirectDeps = workspace.getBazelProjectView().deriveTargetsFromDirectories();
            var outputGroups = Set.of(OutputGroup.INFO, OutputGroup.RESOLVE);
            var outputGroupNames = aspects.getOutputGroupNames(outputGroups, languages, onlyDirectDeps);
            if (scope == BazelClasspathScope.RUNTIME_CLASSPATH) {
                outputGroupNames = new HashSet<>(outputGroupNames);
                outputGroupNames.add(IntellijAspects.OUTPUT_GROUP_JAVA_RUNTIME_CLASSPATH);
            }
            var command = new BazelBuildWithIntelliJAspectsCommand(
                    workspaceRoot,
                    targetsToBuild,
                    outputGroupNames,
                    aspects,
                    new BazelWorkspaceBlazeInfo(workspace),
                    "Running build with IntelliJ aspects to collect classpath information");

            // sync_flags
            command.addCommandArgs(workspace.getBazelProjectView().syncFlags());

            monitor.subTask("Running Bazel build with aspects");
            var result = workspace.getCommandExecutor()
                    .runDirectlyWithinExistingWorkspaceLock(
                        command,
                        bazelProjects.stream().map(BazelProject::getProject).collect(toList()),
                        monitor.split(1, SUPPRESS_ALL_LABELS));

            // populate map from result
            Map<BazelProject, CompileAndRuntimeClasspath> classpathsByProject = new HashMap<>();
            var aspectsInfo = new JavaAspectsInfo(result, workspace, aspects);

            // Performance optimization: Pre-load all dependency packages to avoid repeated Bazel queries
            try {
                var packagesToPreload = collectDependencyPackages(bazelProjects, aspectsInfo, workspace);
                if (!packagesToPreload.isEmpty()) {
                    LOG.debug(
                        "Pre-loading {} dependency packages to optimize classpath computation",
                        packagesToPreload.size());
                    workspace.open(packagesToPreload);
                }
            } catch (Exception e) {
                LOG.warn("Failed to pre-load dependency packages, continuing without optimization", e);
            }

            for (BazelProject bazelProject : bazelProjects) {
                monitor.subTask(bazelProject.getName());
                monitor.checkCanceled();

                if (!bazelProject.isTargetProject()) {
                    monitor.worked(1);
                    continue;
                }

                // build index of classpath info
                var classpathInfo =
                        new JavaAspectsClasspathInfo(aspectsInfo, workspace, availableDependencies, bazelProject);

                // remove old marker
                deleteClasspathContainerProblems(bazelProject);

                // add the primary target
                var problem = classpathInfo.addTarget(bazelProject.getBazelTarget());
                if (!problem.isOK()) {
                    createClasspathContainerProblem(bazelProject, problem);
                }

                // add unprovisioned sibling targets so their deps are included in the classpath
                var siblings = siblingTargetsMap.getOrDefault(bazelProject, List.of());
                for (BazelTarget sibling : siblings) {
                    var siblingProblem = classpathInfo.addTarget(sibling);
                    if (!siblingProblem.isOK()) {
                        createClasspathContainerProblem(bazelProject, siblingProblem);
                    }
                }

                // compute the classpath
                var classpath = classpathInfo.compute();

                classpathsByProject.put(bazelProject, classpath);
                monitor.worked(1);
            }

            return classpathsByProject;
        } finally {
            if (progress != null) {
                progress.done();
            }
        }
    }

    private JavaProjectInfo collectJavaInfoForAnalysis(BazelPackage bazelPackage, BazelTarget sampleTarget)
            throws CoreException {
        var javaInfo = new JavaProjectInfo(bazelPackage);
        var attributes = sampleTarget.getRuleAttributes();
        var srcs = attributes.getStringList(BazelRuleAttribute.SRCS);
        if (srcs != null) {
            for (String src : srcs) {
                javaInfo.addSrc(src, null);
            }
        }
        javaInfo.analyzeProjectRecommendations(false, new NullProgressMonitor());
        return javaInfo;
    }

    @Override
    protected List<BazelProject> doProvisionProjects(Collection<BazelTarget> targets, TracingSubMonitor monitor)
            throws CoreException {
        monitor.setWorkRemaining(targets.size());
        List<BazelProject> result = new ArrayList<>();

        var targetsByPackage = targets.stream()
                .collect(groupingBy(BazelTarget::getBazelPackage, LinkedHashMap::new, toList()));

        for (var entry : targetsByPackage.entrySet()) {
            var bazelPackage = entry.getKey();
            var packageTargets = entry.getValue();

            if (packageTargets.size() > 1 && hasEmptySourceRoot(bazelPackage, packageTargets)) {
                LOG.debug("Detected empty source root for package '{}', merging {} targets into one project",
                    bazelPackage, packageTargets.size());
                var project = provisionMergedTargetProject(bazelPackage, packageTargets, monitor);
                if (project != null) {
                    result.add(project);
                }
            } else {
                for (BazelTarget target : packageTargets) {
                    monitor.subTask(target.getLabel().toString());
                    var project = provisionProjectForTarget(target, monitor);
                    if (project != null) {
                        result.add(project);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Returns unprovisioned sibling java_* targets in the same package. For merged projects, these are the targets that
     * were skipped during provisioning but whose dependencies should be included in classpath computation.
     */
    private List<BazelTarget> getUnprovisionedSiblingTargets(BazelProject bazelProject) throws CoreException {
        var primaryTarget = bazelProject.getBazelTarget();
        var bazelPackage = primaryTarget.getBazelPackage();
        List<BazelTarget> siblings = new ArrayList<>();
        for (BazelTarget t : bazelPackage.getBazelTargets()) {
            if (t.getTargetName().equals(primaryTarget.getTargetName())) {
                continue;
            }
            if (t.hasBazelProject()) {
                continue;
            }
            if (isJavaRule(t.getRuleClass())) {
                siblings.add(t);
            }
        }
        return siblings;
    }

    /**
     * Merges multiple targets sharing an empty source root into a single target project. The primary target (the
     * java_library with the most sources) is used for the project identity, but all targets' source files are included.
     */
    private BazelProject provisionMergedTargetProject(BazelPackage bazelPackage, List<BazelTarget> allTargets,
            TracingSubMonitor monitor) throws CoreException {
        var primaryTarget = selectPrimaryTarget(allTargets);
        monitor.subTask(primaryTarget.getLabel().toString());
        monitor = monitor.split(1, "Provisioning merged project for " + primaryTarget.getLabel());

        var project = provisionTargetProject(primaryTarget, monitor.slice(1));

        // Build Java info from ALL targets in the package
        var javaInfo = collectJavaInfo(project, allTargets, monitor.slice(1));

        // Configure links and classpath using combined info
        linkSourcesIntoProject(project, javaInfo, monitor.slice(1));
        linkGeneratedSourcesIntoProject(project, javaInfo, monitor.slice(1));
        linkJarsIntoProject(project, javaInfo, monitor.slice(1));
        configureRawClasspath(project, javaInfo, monitor.slice(1));

        return project;
    }

    /**
     * Selects the primary target from a list of targets. Prefers the java_library with the most source files.
     */
    private BazelTarget selectPrimaryTarget(List<BazelTarget> targets) throws CoreException {
        BazelTarget primary = null;
        int maxSrcs = -1;
        for (BazelTarget target : targets) {
            if (!"java_library".equals(target.getRuleClass())) {
                continue;
            }
            var srcs = target.getRuleAttributes().getStringList(BazelRuleAttribute.SRCS);
            int count = (srcs != null) ? srcs.size() : 0;
            if (count > maxSrcs) {
                maxSrcs = count;
                primary = target;
            }
        }
        return primary != null ? primary : targets.get(0);
    }

    private boolean hasEmptySourceRoot(BazelPackage bazelPackage, List<BazelTarget> targets) {
        for (BazelTarget target : targets) {
            try {
                var javaInfo = collectJavaInfoForAnalysis(bazelPackage, target);
                if (javaInfo.getSourceInfo().hasSourceDirectories()
                        && javaInfo.getSourceInfo().getSourceDirectories().stream().anyMatch(IPath::isEmpty)) {
                    return true;
                }
            } catch (CoreException e) {
                LOG.debug("Failed to analyze target '{}': {}", target, e.getMessage());
            }
        }
        return false;
    }

    private boolean isJavaRule(String ruleClass) {
        return "java_library".equals(ruleClass) || "java_import".equals(ruleClass)
                || "java_binary".equals(ruleClass) || "java_test".equals(ruleClass);
    }

    protected BazelProject provisionJavaBinaryProject(BazelTarget target, TracingSubMonitor monitor)
            throws CoreException {
        // TODO: create a shared launch configuration
        return provisionJavaLibraryProject(target, monitor);
    }

    protected BazelProject provisionJavaImportProject(BazelTarget target, TracingSubMonitor monitor)
            throws CoreException {
        // java_import is implicitly supported
        return provisionJavaLibraryProject(target, monitor);
    }

    /**
     * Provisions a Java project for the specified {@link BazelTarget}
     *
     * @param target
     *            the <code>java_library</code> target
     * @param progress
     *            monitor for reporting progress and tracking cancellation
     * @return the provisioned project
     * @throws CoreException
     */
    protected BazelProject provisionJavaLibraryProject(BazelTarget target, TracingSubMonitor monitor)
            throws CoreException {
        monitor = monitor.split(1, "Provisioning Java project for target " + target.getLabel());

        var project = provisionTargetProject(target, monitor.slice(1));

        // build the Java information
        var javaInfo = collectJavaInfo(project, List.of(target), monitor.slice(1));

        // configure links
        linkSourcesIntoProject(project, javaInfo, monitor.slice(1));
        linkGeneratedSourcesIntoProject(project, javaInfo, monitor.slice(1));
        linkJarsIntoProject(project, javaInfo, monitor.slice(1));

        // configure classpath
        configureRawClasspath(project, javaInfo, monitor.slice(1));

        return project;
    }

    protected BazelProject provisionJavaTestProject(BazelTarget target, TracingSubMonitor monitor)
            throws CoreException {
        // there is a bug in Eclipse preventing execution of JUnit tests
        // https://github.com/eclipse-jdt/eclipse.jdt.ui/issues/957
        return provisionJavaLibraryProject(target, monitor);
    }

    protected BazelProject provisionProjectForTarget(BazelTarget target, TracingSubMonitor monitor)
            throws CoreException {
        var ruleName = target.getRuleClass();
        return switch (ruleName) {
            case "java_library": {
                yield provisionJavaLibraryProject(target, monitor);
            }
            case "java_import": {
                yield provisionJavaImportProject(target, monitor);
            }
            case "java_binary": {
                yield provisionJavaBinaryProject(target, monitor);
            }
            case "java_test": {
                yield provisionJavaTestProject(target, monitor);
            }
            default: {
                LOG.debug("{}: Skipping provisioning due to unsupported rule '{}'.", target, ruleName);
                monitor.worked(1);
                yield null;
            }
        };
    }

    protected BazelProject provisionTargetProject(BazelTarget target, IProgressMonitor monitor) throws CoreException {
        if (target.hasBazelProject()) {
            return target.getBazelProject();
        }

        var packagePath = getProjectNameFriendlyPackagePath(target.getBazelPackage());
        var projectName = format(
            getTargetProvisioningSetting(target, PROJECT_NAME_FORMAT, "%s - %s"),
            packagePath,
            target.getTargetName().replace('/', '.'));
        var projectLocation = getFileSystemMapper().getProjectsArea().append(projectName);

        createProjectForElement(projectName, projectLocation, target, monitor);
        target.rediscoverBazelProject();

        // this call is no longer expected to fail now (unless we need to poke the element info cache manually here)
        return target.getBazelProject();
    }

}
