/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.coding.tools.git

import com.embabel.agent.tools.DirectoryBased
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File

/**
 * Uses JGit to perform Git operations on a local repository.
 */
interface GitOperations : DirectoryBased {

    private fun openRepository(): Repository {
        return FileRepositoryBuilder()
            .setGitDir(File(root, ".git"))
            .readEnvironment()
            .findGitDir()
            .build()
    }

    private fun openGit(): Git {
        return Git(openRepository())
    }

    fun currentBranch(): String {
        openRepository().use { repo ->
            return repo.branch
        }
    }

    fun listBranches(): List<String> {
        openGit().use { git ->
            return git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call()
                .map { ref ->
                    ref.name.removePrefix("refs/heads/").removePrefix("refs/remotes/origin/")
                }
                .distinct()
        }
    }

    fun checkoutBranch(branch: String): Boolean {
        return try {
            openGit().use { git ->
                git.checkout()
                    .setName(branch)
                    .call()
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun createAndCheckoutBranch(branch: String): Boolean {
        return try {
            openGit().use { git ->
                git.checkout()
                    .setCreateBranch(true)
                    .setName(branch)
                    .call()

                // Create an initial empty commit to establish the branch
                git.commit()
                    .setMessage("Initial commit for branch $branch")
                    .setAllowEmpty(true)
                    .call()
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun pullLatestChanges(): Boolean {
        return try {
            openGit().use { git ->
                git.pull().call()
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun deleteBranch(branch: String): Boolean {
        return try {
            openGit().use { git ->
                git.branchDelete()
                    .setBranchNames(branch)
                    .setForce(true)
                    .call()
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun commit(message: String, addAll: Boolean = true): Boolean {
        return try {
            openGit().use { git ->
                if (addAll) {
                    git.add().addFilepattern(".").call()
                }
                git.commit()
                    .setMessage(message)
                    .call()
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun revert(): Boolean {
        return try {
            openGit().use { git ->
                val repo = git.repository
                val headCommit = repo.resolve("HEAD")
                git.revert()
                    .include(headCommit)
                    .call()
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun revert(commitHash: String): Boolean {
        return try {
            openGit().use { git ->
                val repo = git.repository
                val objectId = repo.resolve(commitHash)
                git.revert()
                    .include(objectId)
                    .call()
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
