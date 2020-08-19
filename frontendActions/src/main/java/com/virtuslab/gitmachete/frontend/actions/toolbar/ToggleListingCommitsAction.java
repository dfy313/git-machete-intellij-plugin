package com.virtuslab.gitmachete.frontend.actions.toolbar;

import static com.virtuslab.gitmachete.frontend.resourcebundles.GitMacheteBundle.getString;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Toggleable;
import com.intellij.openapi.project.DumbAware;
import lombok.CustomLog;
import org.checkerframework.checker.guieffect.qual.UIEffect;

import com.virtuslab.gitmachete.frontend.actions.base.BaseGitMacheteRepositoryReadyAction;
import com.virtuslab.gitmachete.frontend.actions.expectedkeys.IExpectsKeyGitMacheteRepository;
import com.virtuslab.gitmachete.frontend.actions.expectedkeys.IExpectsKeyProject;
import com.virtuslab.logger.IEnhancedLambdaLogger;

@CustomLog
public class ToggleListingCommitsAction extends BaseGitMacheteRepositoryReadyAction
    implements
      DumbAware,
      IExpectsKeyGitMacheteRepository,
      IExpectsKeyProject {

  @Override
  public IEnhancedLambdaLogger log() {
    return LOG;
  }

  @Override
  @UIEffect
  public void onUpdate(AnActionEvent anActionEvent) {
    super.onUpdate(anActionEvent);

    var presentation = anActionEvent.getPresentation();
    boolean selected = Toggleable.isSelected(presentation);
    Toggleable.setSelected(presentation, selected);
    if (!presentation.isEnabledAndVisible()) {
      return;
    }

    var branchLayout = getBranchLayoutWithoutLogging(anActionEvent);
    if (branchLayout.isEmpty()) {
      presentation.setEnabled(false);
      presentation.setDescription(getString("action.GitMachete.ToggleListingCommitsAction.description.disabled.no-branches"));
      return;
    }

    boolean anyCommitExists = getGitMacheteRepositorySnapshotWithoutLogging(anActionEvent)
        .map(repo -> repo.getManagedBranches()
            .exists(b -> b.isNonRoot() && b.asNonRoot().getCommits().nonEmpty()))
        .getOrElse(false);

    if (anyCommitExists) {
      presentation.setEnabled(true);
      presentation.setDescription(getString("action.GitMachete.ToggleListingCommitsAction.description"));
    } else {
      presentation.setEnabled(false);
      presentation.setDescription(getString("action.GitMachete.ToggleListingCommitsAction.description.disabled.no-commits"));
    }

  }

  @Override
  @UIEffect
  public final void actionPerformed(AnActionEvent anActionEvent) {
    boolean newState = !Toggleable.isSelected(anActionEvent.getPresentation());
    log().debug("Triggered with newState = ${newState}");

    var graphTable = getGraphTable(anActionEvent);
    graphTable.setListingCommits(newState);
    graphTable.refreshModel();

    var presentation = anActionEvent.getPresentation();
    Toggleable.setSelected(presentation, newState);
  }
}
