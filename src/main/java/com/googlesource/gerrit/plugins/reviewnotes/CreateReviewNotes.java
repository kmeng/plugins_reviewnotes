// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.reviewnotes;

import com.google.gerrit.common.Nullable;
import com.google.gerrit.common.data.LabelType;
import com.google.gerrit.common.data.LabelTypes;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.ApprovalsUtil;
import com.google.gerrit.server.GerritPersonIdent;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.config.AnonymousCowardName;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gerrit.server.git.NotesBranchUtil;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.InternalChangeQuery;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;

import org.eclipse.jgit.api.errors.ConcurrentRefUpdateException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

class CreateReviewNotes {

  private static final Logger log =
      LoggerFactory.getLogger(CreateReviewNotes.class);

  interface Factory {
    CreateReviewNotes create(ReviewDb reviewDb, Project.NameKey project,
        Repository git);
  }

  private static final String REFS_NOTES_REVIEW = "refs/notes/review";

  private final PersonIdent gerritServerIdent;
  private final AccountCache accountCache;
  private final String anonymousCowardName;
  private final LabelTypes labelTypes;
  private final ApprovalsUtil approvalsUtil;
  private final ChangeControl.GenericFactory changeControlFactory;
  private final ChangeNotes.Factory notesFactory;
  private final IdentifiedUser.GenericFactory userFactory;
  private final NotesBranchUtil.Factory notesBranchUtilFactory;
  private final Provider<InternalChangeQuery> queryProvider;
  private final String canonicalWebUrl;
  private final ReviewDb reviewDb;
  private final Project.NameKey project;
  private final Repository git;

  private ObjectInserter inserter;
  private NoteMap reviewNotes;
  private StringBuilder message;

  @Inject
  CreateReviewNotes(@GerritPersonIdent PersonIdent gerritIdent,
      AccountCache accountCache,
      @AnonymousCowardName String anonymousCowardName,
      ProjectCache projectCache,
      ApprovalsUtil approvalsUtil,
      ChangeControl.GenericFactory changeControlFactory,
      ChangeNotes.Factory notesFactory,
      IdentifiedUser.GenericFactory userFactory,
      NotesBranchUtil.Factory notesBranchUtilFactory,
      Provider<InternalChangeQuery> queryProvider,
      @Nullable @CanonicalWebUrl String canonicalWebUrl,
      @Assisted ReviewDb reviewDb,
      @Assisted Project.NameKey project,
      @Assisted Repository git) {
    this.gerritServerIdent = gerritIdent;
    this.accountCache = accountCache;
    this.anonymousCowardName = anonymousCowardName;
    ProjectState projectState = projectCache.get(project);
    if (projectState == null) {
      log.error("Could not obtain available labels for project "
          + project.get() + ". Expect missing labels in its review notes.");
      this.labelTypes = new LabelTypes(Collections.<LabelType> emptyList());
    } else {
      this.labelTypes = projectState.getLabelTypes();
    }
    this.approvalsUtil = approvalsUtil;
    this.changeControlFactory = changeControlFactory;
    this.notesFactory = notesFactory;
    this.userFactory = userFactory;
    this.notesBranchUtilFactory = notesBranchUtilFactory;
    this.queryProvider = queryProvider;
    this.canonicalWebUrl = canonicalWebUrl;
    this.reviewDb = reviewDb;
    this.project = project;
    this.git = git;
  }

  void createNotes(String branch, ObjectId oldObjectId, ObjectId newObjectId,
      ProgressMonitor monitor) throws OrmException, IOException {
    if (ObjectId.zeroId().equals(newObjectId)) {
      return;
    }

    try (RevWalk rw = new RevWalk(git)) {
      try {
        RevCommit n = rw.parseCommit(newObjectId);
        rw.markStart(n);
        if (n.getParentCount() == 1 && n.getParent(0).equals(oldObjectId)) {
          rw.markUninteresting(rw.parseCommit(oldObjectId));
        } else {
          markUninteresting(git, branch, rw, oldObjectId);
        }
      } catch (Exception e) {
        log.error(e.getMessage(), e);
        return;
      }

      if (monitor == null) {
        monitor = NullProgressMonitor.INSTANCE;
      }

      for (RevCommit c : rw) {
        PatchSet ps = loadPatchSet(c, branch);
        ChangeNotes notes =
            notesFactory.create(reviewDb, project, ps.getId().getParentKey());
        ObjectId content = createNoteContent(notes, ps);
        if (content != null) {
          monitor.update(1);
          getNotes().set(c, content);
          getMessage().append("* ").append(c.getShortMessage()).append("\n");
        }
      }
    }
  }

  void createNotes(List<ChangeNotes> notes, ProgressMonitor monitor)
      throws OrmException, IOException {
    try (RevWalk rw = new RevWalk(git)) {
      if (monitor == null) {
        monitor = NullProgressMonitor.INSTANCE;
      }

      for (ChangeNotes cn : notes) {
        monitor.update(1);
        PatchSet ps =
            reviewDb.patchSets().get(cn.getChange().currentPatchSetId());
        ObjectId commitId = ObjectId.fromString(ps.getRevision().get());
        RevCommit commit = rw.parseCommit(commitId);
        getNotes().set(commitId, createNoteContent(cn, ps));
        getMessage().append("* ").append(commit.getShortMessage()).append("\n");
      }
    }
  }

  void commitNotes() throws IOException, ConcurrentRefUpdateException {
    try {
      if (reviewNotes == null) {
        return;
      }

      message.insert(0, "Update notes for submitted changes\n\n");
      notesBranchUtilFactory.create(project, git, inserter)
          .commitAllNotes(reviewNotes, REFS_NOTES_REVIEW, gerritServerIdent,
              message.toString());
    } finally {
      if (inserter != null) {
        inserter.close();
      }
    }
  }

  private void markUninteresting(Repository git, String branch, RevWalk rw,
      ObjectId oldObjectId) {
    for (final Ref r : git.getAllRefs().values()) {
      try {
        if (r.getName().equals(branch)) {
          if (!ObjectId.zeroId().equals(oldObjectId)) {
            // For the updated branch the oldObjectId is the tip of uninteresting
            // commit history
            rw.markUninteresting(rw.parseCommit(oldObjectId));
          }
        } else if (r.getName().startsWith(Constants.R_HEADS)
            || r.getName().startsWith(Constants.R_TAGS)) {
          rw.markUninteresting(rw.parseCommit(r.getObjectId()));
        }
      } catch (IncorrectObjectTypeException e) {
        // skip if not parseable as a commit
      } catch (MissingObjectException e) {
        // skip if not parseable as a commit
      } catch (IOException e) {
        // skip if not parseable as a commit
      }
    }
  }

  private ObjectId createNoteContent(ChangeNotes notes, PatchSet ps)
      throws OrmException, IOException {
    HeaderFormatter fmt =
        new HeaderFormatter(gerritServerIdent.getTimeZone(), anonymousCowardName);
    if (ps != null) {
      try {
        createCodeReviewNote(notes, ps, fmt);
        return getInserter().insert(Constants.OBJ_BLOB,
            fmt.toString().getBytes("UTF-8"));
      } catch (NoSuchChangeException e) {
        throw new IOException(e);
      }
    }
    return null;
  }

  private PatchSet loadPatchSet(RevCommit c, String destBranch)
      throws OrmException {
    String hash = c.name();
    for (ChangeData cd : queryProvider.get()
        .byBranchCommit(project.get(), destBranch, hash)) {
      for (PatchSet ps : cd.patchSets()) {
        if (ps.getRevision().matches(hash)) {
          return ps;
        }
      }
    }
    return null; // TODO: createNoCodeReviewNote(branch, c, fmt);
  }

  private void createCodeReviewNote(ChangeNotes notes, PatchSet ps,
      HeaderFormatter fmt) throws OrmException, NoSuchChangeException {
    // This races with the label normalization/writeback done by MergeOp. It may
    // repeat some work, but results should be identical except in the case of
    // an additional race with a permissions change.
    // TODO(dborowitz): These will eventually be stamped in the ChangeNotes at
    // commit time so we will be able to skip this normalization step.
    Change change = notes.getChange();
    ChangeControl ctl = changeControlFactory.controlFor(
        notes, userFactory.create(change.getOwner()));
    PatchSetApproval submit = null;
    for (PatchSetApproval a :
        approvalsUtil.byPatchSet(reviewDb, ctl, ps.getId())) {
      if (a.getValue() == 0) {
        // Ignore 0 values.
      } else if (a.isSubmit()) {
        submit = a;
      } else {
        LabelType type = labelTypes.byLabel(a.getLabelId());
        if (type != null) {
          fmt.appendApproval(type, a.getValue(),
              accountCache.get(a.getAccountId()).getAccount());
        }
      }
    }
    if (submit != null) {
      fmt.appendSubmittedBy(accountCache.get(submit.getAccountId()).getAccount());
      fmt.appendSubmittedAt(submit.getGranted());
    }
    if (canonicalWebUrl != null) {
      fmt.appendReviewedOn(canonicalWebUrl, ps.getId().getParentKey());
    }
    fmt.appendProject(project.get());
    fmt.appendBranch(change.getDest().get());
  }

  private ObjectInserter getInserter() {
    if (inserter == null) {
      inserter = git.newObjectInserter();
    }
    return inserter;
  }

  private NoteMap getNotes() {
    if (reviewNotes == null) {
      reviewNotes = NoteMap.newEmptyMap();
    }
    return reviewNotes;
  }

  private StringBuilder getMessage() {
    if (message == null) {
      message = new StringBuilder();
    }
    return message;
  }
}
