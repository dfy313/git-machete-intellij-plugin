package com.virtuslab.gitcore.gitcorejgit;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.virtuslab.gitcore.gitcoreapi.GitException;
import com.virtuslab.gitcore.gitcoreapi.GitNoSuchBranchException;
import com.virtuslab.gitcore.gitcoreapi.ILocalBranch;
import com.virtuslab.gitcore.gitcoreapi.IRepository;
import lombok.Getter;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Optional;

public class JGitRepository implements IRepository {
    @Getter
    private org.eclipse.jgit.lib.Repository jgitRepo;
    @Getter
    private Git jgitGit;
    @Getter
    private Path repositoryPath;
    @Getter
    private Path gitFolderPath;

    @Inject
    public JGitRepository(@Assisted Path repositoryPath) throws IOException {
        this.repositoryPath = repositoryPath;
        this.gitFolderPath = repositoryPath.resolve(".git");
        jgitRepo = new FileRepository(this.gitFolderPath.toString());
        jgitGit = new Git(jgitRepo);
    }

    @Override
    public Optional<ILocalBranch> getCurrentBranch() throws JGitException {
        Ref r;
        try {
            r = jgitRepo.getRefDatabase().findRef(Constants.HEAD);
        } catch (IOException e) {
            throw new JGitException("Cannot get current branch", e);
        }

        if(r == null)
            throw new JGitException("Error occur while getting current branch ref");

        if(r.isSymbolic())
            return Optional.of(new JGitLocalBranch(this, org.eclipse.jgit.lib.Repository.shortenRefName(r.getTarget().getName())));

        return Optional.empty();
    }

    @Override
    public JGitLocalBranch getLocalBranch(String branchName) throws GitException {
        if(!checkIfBranchExist(JGitLocalBranch.branchesPath + branchName))
            throw new GitNoSuchBranchException(MessageFormat.format("Local branch \"{0}\" does not exist in this repository", branchName));

        return new JGitLocalBranch(this, branchName);
    }

    @Override
    public JGitRemoteBranch getRemoteBranch(String branchName) throws GitException{
        if(!checkIfBranchExist(JGitRemoteBranch.branchesPath + branchName))
            throw new GitNoSuchBranchException(MessageFormat.format("Remote branch \"{0}\" does not exist in this repository", branchName));

        return new JGitRemoteBranch(this, branchName);
    }

    private boolean checkIfBranchExist(String path) throws JGitException{
        RevWalk rw = new RevWalk(jgitRepo);
        RevCommit c;
        try {
            ObjectId o = jgitRepo.resolve(path);

            return o != null;
        } catch (RevisionSyntaxException | IOException e) {
            throw  new JGitException(e);
        }
    }
}
