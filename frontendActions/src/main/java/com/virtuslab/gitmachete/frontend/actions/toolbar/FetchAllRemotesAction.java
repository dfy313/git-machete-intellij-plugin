package com.virtuslab.gitmachete.frontend.actions.toolbar;

import static com.virtuslab.gitmachete.frontend.resourcebundles.GitMacheteBundle.getString;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import git4idea.fetch.GitFetchResult;
import git4idea.fetch.GitFetchSupport;
import lombok.CustomLog;
import lombok.val;
import org.checkerframework.checker.guieffect.qual.UIEffect;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import com.virtuslab.gitmachete.frontend.actions.base.BaseProjectDependentAction;
import com.virtuslab.logger.IEnhancedLambdaLogger;
import com.virtuslab.qual.guieffect.UIThreadUnsafe;

@CustomLog
public class FetchAllRemotesAction extends BaseProjectDependentAction {

  private static final long FETCH_ALL_UP_TO_DATE_TIMEOUT = 60 * 1000;

  private static long lastFetchTimeMillis = 0;

  public static boolean isUpToDate() {
    return System.currentTimeMillis() > lastFetchTimeMillis + FETCH_ALL_UP_TO_DATE_TIMEOUT;
  }

  @Override
  public IEnhancedLambdaLogger log() {
    return LOG;
  }

  @Override
  @UIEffect
  protected void onUpdate(AnActionEvent anActionEvent) {
    super.onUpdate(anActionEvent);

    val project = getProject(anActionEvent);
    val presentation = anActionEvent.getPresentation();
    if (GitFetchSupport.fetchSupport(project).isFetchRunning()) {
      presentation.setEnabled(false);
      presentation.setDescription(getString("action.GitMachete.FetchAllRemotesAction.description.disabled.already-running"));
    } else {
      presentation.setDescription(getString("action.GitMachete.FetchAllRemotesAction.description"));
    }
  }

  @Override
  @UIEffect
  public void actionPerformed(AnActionEvent anActionEvent) {
    log().debug("Performing");

    val project = getProject(anActionEvent);
    val gitRepository = getSelectedGitRepository(anActionEvent);

    String title = getString("action.GitMachete.FetchAllRemotesAction.task-title");
    new Task.Backgroundable(project, title, /* canBeCancelled */ true) {
      private @MonotonicNonNull GitFetchResult result = null;

      @Override
      @UIThreadUnsafe
      public void run(ProgressIndicator indicator) {
        result = GitFetchSupport.fetchSupport(project).fetchAllRemotes(gitRepository.toJavaList());
        lastFetchTimeMillis = System.currentTimeMillis();
      }

      @Override
      @UIEffect
      public void onFinished() {
        val result = this.result;
        if (result != null) {
          result.showNotification();
        }
      }
    }.queue();
  }

}
