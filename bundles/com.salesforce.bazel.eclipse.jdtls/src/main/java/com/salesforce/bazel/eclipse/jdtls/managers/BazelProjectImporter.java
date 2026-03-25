/*-
 * Copyright (c) 2023 Salesforce and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *      Salesforce - initial implementation similar to JDT LS importers
*/

package com.salesforce.bazel.eclipse.jdtls.managers;

import static com.salesforce.bazel.eclipse.core.BazelCoreSharedContstants.BAZEL_NATURE_ID;
import static com.salesforce.bazel.eclipse.core.model.BazelWorkspace.WORKSPACE_BOUNDARY_FILES;
import static java.lang.String.format;
import static java.nio.file.Files.isRegularFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.ls.core.internal.AbstractProjectImporter;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.ProjectUtils;

import com.salesforce.bazel.eclipse.core.BazelCore;
import com.salesforce.bazel.eclipse.core.model.BazelProject;
import com.salesforce.bazel.eclipse.core.model.BazelWorkspace;
import com.salesforce.bazel.eclipse.core.setup.DefaultProjectViewFileInitializer;
import com.salesforce.bazel.eclipse.core.setup.ImportBazelWorkspaceJob;

/**
 * Importer for Bazel projects.
 * <p>
 * The importer is registered with a priority to get triggered before Gradel, Maven, Eclipse and others. This is
 * important so we can handle Bazel projects.
 * </p>
 */
@SuppressWarnings("restriction")
public final class BazelProjectImporter extends AbstractProjectImporter {

    @Override
    public boolean applies(Collection<IPath> projectConfigurations, IProgressMonitor monitor)
            throws OperationCanceledException, CoreException {
        var configurationDirs = findProjectPathByConfigurationName(
            projectConfigurations,
            WORKSPACE_BOUNDARY_FILES,
            false /*includeNested*/);
        if ((configurationDirs == null) || configurationDirs.isEmpty()) {
            return false;
        }

        Set<Path> noneBazelProjectPaths = new HashSet<>();
        for (IProject project : ProjectUtils.getAllProjects()) {
            if (!ProjectUtils.hasNature(project, BAZEL_NATURE_ID)) {
                noneBazelProjectPaths.add(project.getLocation().toPath());
            }
        }

        this.directories = configurationDirs.stream().filter(d -> {
            var folderIsImported = noneBazelProjectPaths.stream().anyMatch(path -> (path.compareTo(d) == 0));
            return !folderIsImported;
        }).collect(Collectors.toList());

        return !this.directories.isEmpty();
    }

    /**
     * {@inheritDoc}
     * <p>
     * <b>Side effect:</b> This method cleans up stale Eclipse project files ({@code .project}, {@code .classpath},
     * {@code .settings}) from a previous session before performing detection. This is necessary because stale
     * metadata can interfere with workspace detection and cause import failures.
     * </p>
     */
    @Override
    public boolean applies(IProgressMonitor monitor) throws OperationCanceledException, CoreException {
        cleanupStaleProjectFilesIfNeeded();

        // Check if Bazel Java integration is enabled via configuration file
        if (!isBazelJavaEnabled()) {
            JavaLanguageServerPlugin.logInfo("Bazel Java integration is disabled via configuration");
            return false;
        }

        if (directories == null) {
            var bazelDetector =
                    new BazelFileDetector(rootFolder.toPath(), WORKSPACE_BOUNDARY_FILES).includeNested(false);

            // exclude all existing non Bazel projects
            for (IProject project : ProjectUtils.getAllProjects()) {
                if (!ProjectUtils.hasNature(project, BAZEL_NATURE_ID)) {
                    var path = project.getLocation().toOSString();
                    bazelDetector.addExclusions(path);
                }
            }

            directories = bazelDetector.scan(monitor);
        }
        return !directories.isEmpty();
    }

    private IPath findExistingOrCreateEmptyProjectView(BazelWorkspace workspace) throws CoreException {
        // use any existing .eclipse/.bazelproject file (important: this dominates any of the logic below)
        var projectViewLocation = workspace.getBazelProjectFileSystemMapper().getProjectViewLocation();
        if (isRegularFile(projectViewLocation.toPath())) {
            return projectViewLocation;
        }

        // create a default one
        JavaLanguageServerPlugin.logInfo("No .bazelproject file found. Generating a default one.");
        try {
            new DefaultProjectViewFileInitializer(workspace.getLocation().toPath())
                    .create(projectViewLocation.toPath());
        } catch (IOException e) {
            throw new CoreException(
                    Status.error(format("Unable to create default project view at '%s'", projectViewLocation), e));
        }
        return projectViewLocation;
    }

    @Override
    public void importToWorkspace(IProgressMonitor progress) throws OperationCanceledException, CoreException {
        if ((directories == null) || directories.isEmpty()) {
            return;
        }

        JavaLanguageServerPlugin.logInfo("Importing Bazel workspace(s)");
        var monitor = SubMonitor.convert(progress, "Importing Bazel workspace(s)", directories.size() * 100);
        var root = ResourcesPlugin.getWorkspace().getRoot();

        for (Path directory : directories) {
            var workspaceLocation = IPath.fromPath(directory);

            // if there is a workspace project we do nothing because it is imported already
            var workspaceContainer = root.getContainerForLocation(workspaceLocation);
            if (workspaceContainer != null) {
                var existingWorkspaceProject = workspaceContainer.getProject();
                if (BazelProject.isBazelProject(existingWorkspaceProject)) {
                    var bazelProject = BazelCore.create(existingWorkspaceProject);
                    if (bazelProject.isWorkspaceProject()
                            && bazelProject.getBazelWorkspace().getLocation().equals(workspaceLocation)) {
                        continue;
                    }
                }
                JavaLanguageServerPlugin.logError(
                    format(
                        "Found an exising project for workspace '%s', which is not a Bazel workspace (%s). Please consider resetting the workspace if the import fails.",
                        workspaceLocation,
                        workspaceContainer));
            }

            var workspace = BazelCore.createWorkspace(new org.eclipse.core.runtime.Path(directory.toString()));

            // find or create project view
            var projectViewLocation = findExistingOrCreateEmptyProjectView(workspace);

            // import workspace
            // note: we don't schedule the job but execute it directly
            var importBazelWorkspaceJob = new ImportBazelWorkspaceJob(workspace, projectViewLocation);
            importBazelWorkspaceJob.runInWorkspace(monitor.split(100));
        }
    }

    private void cleanupStaleProjectFilesIfNeeded() {
        var workspaceRoot = rootFolder.toPath();
        if (!Files.exists(workspaceRoot.resolve(".project"))) {
            return;
        }
        var eclipseRoot = ResourcesPlugin.getWorkspace().getRoot();
        var isStale = false;

        var eclipseProjectsDir = workspaceRoot.resolve(".eclipse").resolve("projects");
        if (Files.isDirectory(eclipseProjectsDir)) {
            try (var children = Files.list(eclipseProjectsDir)) {
                isStale = children.filter(Files::isDirectory).anyMatch(projectDir -> {
                    var container = eclipseRoot.getContainerForLocation(IPath.fromPath(projectDir));
                    return container == null;
                });
            } catch (IOException e) {
                JavaLanguageServerPlugin.logInfo(
                    "Failed to check stale project files in " + eclipseProjectsDir + ": " + e.getMessage());
            }
        }

        if (!isStale) {
            var rootContainer = eclipseRoot.getContainerForLocation(IPath.fromPath(workspaceRoot));
            isStale = rootContainer == null;
        }

        if (isStale) {
            cleanupStaleProjectFiles(workspaceRoot);
        }
    }

    private void cleanupStaleProjectFiles(Path workspaceRoot) {
        JavaLanguageServerPlugin.logInfo("Cleaning up stale project files from previous session in " + workspaceRoot);
        try {
            Files.walkFileTree(workspaceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    var name = dir.getFileName();
                    if (name == null) {
                        return FileVisitResult.CONTINUE;
                    }
                    var dirName = name.toString();
                    // Skip Bazel convenience symlinks (bazel-bin, bazel-out, etc.);
                    // non-symlink dirs starting with "bazel-" are still traversed.
                    if (dirName.startsWith("bazel-") && Files.isSymbolicLink(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (dirName.startsWith(".") && !".settings".equals(dirName) && !".eclipse".equals(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if ("node_modules".equals(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (".settings".equals(dirName)) {
                        JavaLanguageServerPlugin.logInfo("Deleting stale .settings directory: " + dir);
                        deleteRecursively(dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    var fileName = file.getFileName().toString();
                    if (".project".equals(fileName) || ".classpath".equals(fileName)) {
                        try {
                            Files.deleteIfExists(file);
                            JavaLanguageServerPlugin.logInfo("Deleted stale project file: " + file);
                        } catch (IOException e) {
                            JavaLanguageServerPlugin.logInfo(
                                "Failed to delete stale file " + file + ": " + e.getMessage());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });

            var eclipseProjectsDir = workspaceRoot.resolve(".eclipse").resolve("projects");
            if (Files.isDirectory(eclipseProjectsDir)) {
                deleteRecursively(eclipseProjectsDir);
            }
        } catch (IOException e) {
            JavaLanguageServerPlugin.logInfo("Failed to clean up stale project files: " + e.getMessage());
        }
    }

    private static void deleteRecursively(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            JavaLanguageServerPlugin.logInfo(
                "Failed to delete directory recursively " + dir + ": " + e.getMessage());
        }
    }

    @Override
    public void reset() {
        directories = null;
    }

    /**
     * Checks if Bazel Java integration is enabled by reading the configuration file.
     * <p>
     * This method reads the {@code .vscode/.bazel-java-enabled} file from the workspace root to determine whether
     * Bazel Java support should be activated. The file is written by the VS Code extension based on the
     * {@code java.bazel.enabled} configuration setting.
     * </p>
     * <p>
     * To handle race conditions during startup, this method will wait briefly (up to 500ms) for the configuration
     * file to appear if it doesn't exist initially. This ensures that the VS Code extension has time to write the
     * configuration before JDTLS makes its activation decision.
     * </p>
     * <p>
     * After successfully reading the configuration, the file is deleted to ensure it doesn't affect subsequent
     * workspace openings. The VS Code extension will regenerate it on the next activation.
     * </p>
     *
     * @return {@code true} if enabled (default), {@code false} if explicitly disabled
     */
    private boolean isBazelJavaEnabled() {
        try {
            // Get workspace root directory
            if (rootFolder == null) {
                return true; // Default to enabled if no root folder
            }

            Path workspaceRoot = rootFolder.toPath();
            Path configFile = workspaceRoot.resolve(".vscode").resolve(".bazel-java-enabled");

            // Wait for config file to appear (max 500ms)
            // This handles race conditions where JDTLS starts before VS Code extension writes the config
            boolean configFileFound = false;
            for (int attempt = 0; attempt < 50; attempt++) {
                if (Files.exists(configFile)) {
                    configFileFound = true;
                    break;
                }
                try {
                    Thread.sleep(10); // Wait 10ms between checks
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    JavaLanguageServerPlugin.logInfo("Interrupted while waiting for Bazel config file");
                    return true; // Default to enabled on interruption
                }
            }

            // If config file doesn't exist after waiting, default to enabled
            if (!configFileFound) {
                JavaLanguageServerPlugin.logInfo(
                    "No .bazel-java-enabled config file found after waiting. Defaulting to enabled.");
                return true;
            }

            // Read file content
            String content = Files.readString(configFile, StandardCharsets.UTF_8).trim();
            boolean enabled = !"false".equalsIgnoreCase(content);

            JavaLanguageServerPlugin.logInfo(
                format("Bazel Java integration %s based on config file", enabled ? "enabled" : "disabled"));

            // Delete the config file after reading to ensure clean state for next startup
            // The VS Code extension will recreate it on next activation
            try {
                Files.deleteIfExists(configFile);
                JavaLanguageServerPlugin.logInfo("Deleted temporary config file .bazel-java-enabled");
            } catch (IOException e) {
                // Log but don't fail if deletion fails - file will be overwritten next time
                JavaLanguageServerPlugin.logInfo(
                    format("Could not delete config file (will be overwritten on next start): %s", e.getMessage()));
            }

            return enabled;

        } catch (Exception e) {
            // On any error, default to enabled to avoid breaking Java projects
            JavaLanguageServerPlugin.logError(
                format("Failed to read Bazel enabled configuration: %s. Defaulting to enabled.", e.getMessage()));
            return true;
        }
    }
}
