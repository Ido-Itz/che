/*
 * Copyright (c) 2012-2018 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.api.git;

import static java.util.Collections.singletonList;
import static org.eclipse.che.api.fs.server.WsPathUtils.SEPARATOR;
import static org.eclipse.che.api.fs.server.WsPathUtils.absolutize;
import static org.eclipse.che.api.fs.server.WsPathUtils.resolve;
import static org.eclipse.che.api.project.server.VcsStatusProvider.VcsStatus.ADDED;
import static org.eclipse.che.api.project.server.VcsStatusProvider.VcsStatus.MODIFIED;
import static org.eclipse.che.api.project.server.VcsStatusProvider.VcsStatus.NOT_MODIFIED;
import static org.eclipse.che.api.project.server.VcsStatusProvider.VcsStatus.UNTRACKED;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.model.workspace.config.ProjectConfig;
import org.eclipse.che.api.core.notification.EventService;
import org.eclipse.che.api.fs.server.PathTransformer;
import org.eclipse.che.api.git.exception.GitException;
import org.eclipse.che.api.git.shared.Status;
import org.eclipse.che.api.git.shared.StatusChangedEventDto;
import org.eclipse.che.api.project.server.ProjectManager;
import org.eclipse.che.api.project.server.VcsStatusProvider;
import org.eclipse.che.api.project.server.notification.ProjectDeletedEvent;

/**
 * Git implementation of {@link VcsStatusProvider}.
 *
 * @author Igor Vinokur
 */
public class GitStatusProvider implements VcsStatusProvider {

  private final GitConnectionFactory gitConnectionFactory;
  private final PathTransformer pathTransformer;
  private final ProjectManager projectManager;
  private final Map<String, Status> statusCache;

  @Inject
  public GitStatusProvider(
      GitConnectionFactory gitConnectionFactory,
      PathTransformer pathTransformer,
      ProjectManager projectManager,
      EventService eventService) {
    this.gitConnectionFactory = gitConnectionFactory;
    this.pathTransformer = pathTransformer;
    this.projectManager = projectManager;
    this.statusCache = new HashMap<>();

    eventService.subscribe(
        event -> statusCache.put(event.getProjectName(), event.getStatus()),
        StatusChangedEventDto.class);

    eventService.subscribe(
        event ->
            statusCache.remove(
                event.getProjectPath().substring(event.getProjectPath().lastIndexOf('/') + 1)),
        ProjectDeletedEvent.class);
  }

  @Override
  public String getVcsName() {
    return GitProjectType.TYPE_ID;
  }

  @Override
  public VcsStatus getStatus(String wsPath) throws ServerException {
    try {
      ProjectConfig project =
          projectManager
              .getClosest(wsPath)
              .orElseThrow(() -> new NotFoundException("Can't find project"));
      String projectFsPath = pathTransformer.transform(project.getPath()).toString();
      wsPath = wsPath.substring(wsPath.startsWith(SEPARATOR) ? 1 : 0);
      String itemPath = wsPath.substring(wsPath.indexOf(SEPARATOR) + 1);
      Status cachedStatus = statusCache.get(project.getName());
      Status status =
          gitConnectionFactory.getConnection(projectFsPath).status(singletonList(itemPath));
      if (cachedStatus == null) {
        cachedStatus = status;
      }
      if (status.getUntracked().contains(itemPath)) {
        cachedStatus.getUntracked().add(itemPath);
        return UNTRACKED;
      } else if (status.getAdded().contains(itemPath)) {
        cachedStatus.getAdded().add(itemPath);
        return ADDED;
      } else if (status.getModified().contains(itemPath)) {
        cachedStatus.getModified().add(itemPath);
        return MODIFIED;
      } else if (status.getChanged().contains(itemPath)) {
        cachedStatus.getChanged().add(itemPath);
        return MODIFIED;
      } else {
        return NOT_MODIFIED;
      }
    } catch (GitException | NotFoundException e) {
      throw new ServerException(e.getMessage());
    }
  }

  @Override
  public Map<String, VcsStatus> getStatus(String wsPath, List<String> paths)
      throws ServerException {
    Map<String, VcsStatus> result = new HashMap<>();
    try {
      ProjectConfig project =
          projectManager
              .getClosest(absolutize(wsPath))
              .orElseThrow(() -> new NotFoundException("Can't find project"));
      String projectName = project.getName();
      if (statusCache.get(projectName) == null) {
        String projectFsPath = pathTransformer.transform(project.getPath()).toString();
        statusCache.put(
            projectName, gitConnectionFactory.getConnection(projectFsPath).status(paths));
      }
      Status status = statusCache.get(projectName);
      paths.forEach(
          path -> {
            String itemWsPath = resolve(project.getPath(), path);
            if (status.getUntracked().contains(path)) {
              result.put(itemWsPath, UNTRACKED);
            } else if (status.getAdded().contains(path)) {
              result.put(itemWsPath, ADDED);
            } else if (status.getModified().contains(path) || status.getChanged().contains(path)) {
              result.put(itemWsPath, MODIFIED);
            } else {
              result.put(itemWsPath, NOT_MODIFIED);
            }
          });

    } catch (NotFoundException e) {
      throw new ServerException(e.getMessage());
    }
    return result;
  }

  class CachedStatus {

    List<String> added = new ArrayList<>();
    List<String> untracked = new ArrayList<>();
    List<String> changed = new ArrayList<>();
    List<String> modified = new ArrayList<>();

    public List<String> getAdded() {
      return added;
    }

    public void setAdded(List<String> added) {
      this.added = added;
    }

    public List<String> getUntracked() {
      return untracked;
    }

    public void setUntracked(List<String> untracked) {
      this.untracked = untracked;
    }

    public List<String> getChanged() {
      return changed;
    }

    public void setChanged(List<String> changed) {
      this.changed = changed;
    }

    public List<String> getModified() {
      return modified;
    }

    public void setModified(List<String> modified) {
      this.modified = modified;
    }
  }
}
