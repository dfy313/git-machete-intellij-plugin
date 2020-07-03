package com.virtuslab.gitmachete.frontend.actions.base;

import static io.vavr.API.$;
import static io.vavr.API.Case;
import static io.vavr.API.Match;

import com.intellij.dvcs.repo.Repository;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsNotifier;
import git4idea.branch.GitRebaseParams;
import git4idea.config.GitVersion;
import git4idea.rebase.GitRebaseUtils;
import git4idea.repo.GitRepository;
import git4idea.util.GitFreezingProcess;
import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.CustomLog;
import org.checkerframework.checker.guieffect.qual.UIEffect;

import com.virtuslab.gitmachete.backend.api.IGitMacheteBranch;
import com.virtuslab.gitmachete.backend.api.IGitMacheteNonRootBranch;
import com.virtuslab.gitmachete.backend.api.IGitMacheteRepositorySnapshot;
import com.virtuslab.gitmachete.backend.api.IGitRebaseParameters;
import com.virtuslab.gitmachete.backend.api.SyncToParentStatus;
import com.virtuslab.gitmachete.backend.api.hook.IExecutionResult;
import com.virtuslab.gitmachete.frontend.actions.expectedkeys.IExpectsKeyGitMacheteRepository;
import com.virtuslab.gitmachete.frontend.actions.expectedkeys.IExpectsKeyProject;
import com.virtuslab.gitmachete.frontend.defs.ActionPlaces;
import com.virtuslab.logger.IEnhancedLambdaLogger;

@CustomLog
public abstract class BaseRebaseBranchOntoParentAction extends BaseGitMacheteRepositoryReadyAction
    implements
      IBranchNameProvider,
      IExpectsKeyProject,
      IExpectsKeyGitMacheteRepository {
  private static final String NL = System.lineSeparator();

  @Override
  public IEnhancedLambdaLogger log() {
    return LOG;
  }

  @Override
  @UIEffect
  public void update(AnActionEvent anActionEvent) {
    super.update(anActionEvent);

    if (!canBeUpdated()) {
      return;
    }

    var presentation = anActionEvent.getPresentation();
    if (!presentation.isEnabledAndVisible()) {
      return;
    }

    var state = getSelectedGitRepository(anActionEvent).map(r -> r.getState());

    if (state.isEmpty()) {
      presentation.setEnabled(false);
      presentation.setDescription("Can't rebase due to unknown repository state");

    } else if (state.get() != Repository.State.NORMAL) {

      var stateName = Match(state.get()).of(
          Case($(Repository.State.GRAFTING), "during an ongoing cherry-pick"),
          Case($(Repository.State.DETACHED), "in the detached head state"),
          Case($(Repository.State.MERGING), "during an ongoing merge"),
          Case($(Repository.State.REBASING), "during an ongoing rebase"),
          Case($(Repository.State.REVERTING), "during an ongoing revert"),
          Case($(), ": " + state.get().name().toLowerCase()));

      presentation.setEnabled(false);
      presentation.setDescription("Can't rebase ${stateName}");
    } else {

      var branchName = getNameOfBranchUnderAction(anActionEvent);
      var branch = branchName.flatMap(bn -> getGitMacheteBranchByName(anActionEvent, bn));

      if (branch.isEmpty()) {
        presentation.setEnabled(false);
        presentation.setDescription("Rebase disabled due to undefined selected branch");
      } else if (branch.get().isRootBranch()) {

        if (anActionEvent.getPlace().equals(ActionPlaces.ACTION_PLACE_TOOLBAR)) {
          presentation.setEnabled(false);
          presentation.setDescription("Root branch '${branch.get().getName()}' cannot be rebased");
        } else { //contextmenu
          // in case of root branch we do not want to show this option at all
          presentation.setEnabledAndVisible(false);
        }

      } else if (branch.get().asNonRootBranch().getSyncToParentStatus() == SyncToParentStatus.MergedToParent) {
        presentation.setEnabled(false);
        presentation.setDescription("Can't rebase merged branch '${branch.get().getName()}'");

      } else if (branch.get().isNonRootBranch()) {
        var nonRootBranch = branch.get().asNonRootBranch();
        IGitMacheteBranch upstream = nonRootBranch.getUpstreamBranch();
        presentation.setDescription("Rebase '${branch.get().getName()}' onto '${upstream.getName()}'");
      }

      var isRebasingCurrent = branch.isDefined() && getCurrentBranchNameIfManaged(anActionEvent)
          .map(bn -> bn.equals(branch.get().getName())).getOrElse(false);
      if (anActionEvent.getPlace().equals(ActionPlaces.ACTION_PLACE_CONTEXT_MENU) && isRebasingCurrent) {
        presentation.setText("_Rebase Branch onto Parent");
      }
    }
  }

  @Override
  public void actionPerformed(AnActionEvent anActionEvent) {
    LOG.debug("Performing");

    var branchName = getNameOfBranchUnderAction(anActionEvent);
    var branch = branchName.flatMap(bn -> getGitMacheteBranchByName(anActionEvent, bn));

    if (branch.isDefined()) {
      if (branch.get().isNonRootBranch()) {
        doRebase(anActionEvent, branch.get().asNonRootBranch());
      } else {
        LOG.warn("Skipping the action because the branch '${branch.get().getName()}' is a root branch");
      }
    }
  }

  private void doRebase(AnActionEvent anActionEvent, IGitMacheteNonRootBranch branchToRebase) {
    var project = getProject(anActionEvent);
    var gitRepository = getSelectedGitRepository(anActionEvent);
    var gitMacheteRepositorySnapshot = getGitMacheteRepositorySnapshotWithLoggingOnEmpty(anActionEvent);

    if (gitRepository.isDefined() && gitMacheteRepositorySnapshot.isDefined()) {
      doRebase(project, gitRepository.get(), gitMacheteRepositorySnapshot.get(), branchToRebase);
    }
  }

  private void doRebase(
      Project project,
      GitRepository gitRepository,
      IGitMacheteRepositorySnapshot gitMacheteRepositorySnapshot,
      IGitMacheteNonRootBranch branchToRebase) {
    LOG.debug(() -> "Entering: project = ${project}, gitRepository = ${gitRepository}, branchToRebase = ${branchToRebase}");

    var tryGitRebaseParameters = Try.of(() -> branchToRebase.getParametersForRebaseOntoParent());

    if (tryGitRebaseParameters.isFailure()) {
      var e = tryGitRebaseParameters.getCause();
      // TODO (#172): redirect the user to the manual fork-point
      var message = e.getMessage() == null ? "Unable to get rebase parameters." : e.getMessage();
      LOG.error(message);
      VcsNotifier.getInstance(project).notifyError("Rebase failed", message);
      return;
    }

    var gitRebaseParameters = tryGitRebaseParameters.get();
    LOG.debug(() -> "Queuing machete-pre-rebase hook background task for '${branchToRebase.getName()}' branch");

    new Task.Backgroundable(project, "Running machete-pre-rebase hook") {
      @Override
      public void run(ProgressIndicator indicator) {

        var wrapper = new Object() {
          Try<Option<IExecutionResult>> hookResult = Try.success(Option.none());
        };
        new GitFreezingProcess(project, myTitle, () -> {
          LOG.info("Executing machete-pre-rebase hook");
          wrapper.hookResult = Try
              .of(() -> gitMacheteRepositorySnapshot.executeMachetePreRebaseHookIfPresent(gitRebaseParameters));
        }).execute();
        var hookResult = wrapper.hookResult;

        if (hookResult.isFailure()) {
          var message = "machete-pre-rebase hook refused to rebase ${NL}error: ${hookResult.getCause().getMessage()}";
          LOG.error(message);
          VcsNotifier.getInstance(project).notifyError("Rebase aborted", message);
          return;
        }

        var maybeExecutionResult = hookResult.get();
        if (maybeExecutionResult.isDefined() && maybeExecutionResult.get().getExitCode() != 0) {
          var message = "machete-pre-rebase hook refused to rebase (exit code ${maybeExecutionResult.get().getExitCode()})";
          LOG.error(message);
          var executionResult = maybeExecutionResult.get();
          var stdoutOption = executionResult.getStdout();
          var stderrOption = executionResult.getStderr();
          VcsNotifier.getInstance(project).notifyError(
              "Rebase aborted", message
                  + (!stdoutOption.isBlank() ? NL + "stdout:" + NL + stdoutOption : "")
                  + (!stderrOption.isBlank() ? NL + "stderr:" + NL + stderrOption : ""));
          return;
        }

        LOG.debug(() -> "Queuing rebase background task for '${branchToRebase.getName()}' branch");

        new Task.Backgroundable(project, "Rebasing") {
          @Override
          public void run(ProgressIndicator indicator) {
            GitRebaseParams params = getIdeaRebaseParamsOf(gitRepository, gitRebaseParameters);
            LOG.info("Rebasing '${gitRebaseParameters.getCurrentBranch().getName()}' branch " +
                "until ${gitRebaseParameters.getForkPointCommit().getHash()} commit " +
                "onto ${gitRebaseParameters.getNewBaseCommit().getHash()}");

            GitRebaseUtils.rebase(project, java.util.List.of(gitRepository), params, indicator);
          }
        }.queue();
      }

      // TODO (#95): on success, refresh only sync statuses (not the whole repository). Keep in mind potential
      // changes to commits (eg. commits may get squashed so the graph structure changes).
    }.queue();
  }

  private GitRebaseParams getIdeaRebaseParamsOf(GitRepository repository, IGitRebaseParameters gitRebaseParameters) {
    GitVersion gitVersion = repository.getVcs().getVersion();
    String currentBranch = gitRebaseParameters.getCurrentBranch().getName();
    String newBase = gitRebaseParameters.getNewBaseCommit().getHash();
    String forkPoint = gitRebaseParameters.getForkPointCommit().getHash();

    return new GitRebaseParams(gitVersion, currentBranch, newBase, /* upstream */ forkPoint,
        /* interactive */ true, /* preserveMerges */ false);
  }
}
