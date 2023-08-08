package com.mattprecious.stacker

import com.github.ajalt.clikt.core.Abort
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.mordant.terminal.ConversionResult
import com.github.ajalt.mordant.terminal.YesNoPrompt
import com.mattprecious.stacker.config.ConfigManager
import com.mattprecious.stacker.config.RealConfigManager
import com.mattprecious.stacker.remote.GitHubRemote
import com.mattprecious.stacker.remote.Remote
import com.mattprecious.stacker.rendering.styleBranch
import com.mattprecious.stacker.rendering.styleCode
import com.mattprecious.stacker.shell.RealShell
import com.mattprecious.stacker.vc.GitVersionControl
import com.mattprecious.stacker.vc.VersionControl
import com.mattprecious.stacker.vc.Branch as VcBranch

class Stacker : CliktCommand(
	name = "st",
) {
	private val shell = RealShell()
	private val vc = GitVersionControl(shell)
	private val configManager = RealConfigManager(vc)
	private val remote = GitHubRemote(vc.originUrl, configManager)

	init {
		subcommands(
			Init(
				configManager = configManager,
			),
			Log(
				configManager = configManager,
				vc = vc,
			),
			Branch(
				vc = vc,
				configManager = configManager,
				remote = remote,
			),
			Stack(
				vc = vc,
				configManager = configManager,
				remote = remote,
			),
		)
	}

	override fun run() {
		if (!configManager.repoInitialized && currentContext.invokedSubcommand !is Init) {
			error(message = "Stacker must be initialized, first. Please run ${"st init".styleCode()}.")
			throw Abort()
		}
	}
}

class Init(
	private val configManager: ConfigManager,
) : CliktCommand() {
	override fun run() {
		// TODO: Infer.
		val trunk = selectBranch(
			text = "Enter the name of your trunk branch, which you open pull requests against",
			default = "main",
		)

		val useTrailing = YesNoPrompt(
			terminal = currentContext.terminal,
			prompt = "Do you use a trailing-trunk workflow?",
			default = false,
		).ask() == true

		val trailingTrunk = if (!useTrailing) {
			null
		} else {
			selectBranch("Enter the name of your trailing trunk branch, which you branch from")
		}

		configManager.initializeRepo(trunk = trunk, trailingTrunk = trailingTrunk)
	}
}

private class Branch(
	vc: VersionControl,
	configManager: ConfigManager,
	remote: Remote,
) : CliktCommand() {
	init {
		subcommands(
			Track(vc, configManager),
			Untrack(vc),
			Create(vc),
			Submit(configManager, remote, vc),
		)
	}

	override fun run() = Unit

	private class Track(
		private val vc: VersionControl,
		private val configManager: ConfigManager,
	) : CliktCommand() {
		override fun run() {
			val branch = vc.currentBranch
			if (branch.tracked) {
				error(message = "Branch ${branch.name.styleBranch()} is already tracked.")
				return
			}

			val parent = selectBranch(
				"Select the parent branch for ${branch.name.styleBranch()}",
				default = configManager.trailingTrunk ?: configManager.trunk,
			)

			branch.track(vc.getBranch(parent))
		}
	}

	private class Untrack(
		private val vc: VersionControl,
	) : CliktCommand() {
		override fun run() {
			val branch = vc.currentBranch
			if (!branch.tracked) {
				error(message = "Branch ${branch.name.styleBranch()} is already not tracked.")
				return
			}

			branch.untrack()
		}
	}

	private class Create(
		private val vc: VersionControl,
	) : CliktCommand() {
		private val branchName by argument()

		override fun run() {
			if (!vc.currentBranch.tracked) {
				error(
					message = "Cannot branch from ${vc.currentBranch.name.styleBranch()} since it is not tracked. " +
						"Please track with ${"st branch track".styleCode()}.",
				)
				throw Abort()
			}

			vc.createBranchFromCurrent(branchName)
		}
	}

	private class Submit(
		private val configManager: ConfigManager,
		private val remote: Remote,
		private val vc: VersionControl,
	) : CliktCommand() {
		override fun run() {
			if (!vc.currentBranch.tracked) {
				error(
					message = "Cannot create a pull request from ${vc.currentBranch.name.styleBranch()} since it is " +
						"not tracked. Please track with ${"st branch track".styleCode()}.",
				)
				throw Abort()
			}

			if (vc.currentBranch.isTrunk) {
				error(
					message = "Cannot create a pull request from trunk branch ${vc.currentBranch.name.styleBranch()}.",
				)
				throw Abort()
			}

			remote.requireAuthenticated()

			vc.pushCurrentBranch()
			vc.currentBranch.submit(configManager, remote, vc)
		}
	}
}

private class Stack(
	configManager: ConfigManager,
	remote: Remote,
	vc: VersionControl,
) : CliktCommand() {
	init {
		subcommands(
			Submit(configManager, remote, vc),
		)
	}

	override fun run() = Unit

	private class Submit(
		private val configManager: ConfigManager,
		private val remote: Remote,
		private val vc: VersionControl,
	) : CliktCommand() {
		override fun run() {
			if (!vc.currentBranch.tracked) {
				error(
					message = "Cannot create a pull request from ${vc.currentBranch.name.styleBranch()} since it is " +
						"not tracked. Please track with ${"st branch track".styleCode()}.",
				)
				throw Abort()
			}

			if (vc.currentBranch.isTrunk) {
				error(
					message = "Cannot create a pull request from trunk branch ${vc.currentBranch.name.styleBranch()}.",
				)
				throw Abort()
			}

			remote.requireAuthenticated()

			val branchesToSubmit = vc.currentBranch.flattenStack()
			vc.pushBranches(branchesToSubmit)
			branchesToSubmit.forEach { it.submit(configManager, remote, vc) }
		}
	}
}

private class Log(
	private val configManager: ConfigManager,
	private val vc: VersionControl,
) : CliktCommand() {
	override fun run() {
		vc.getBranch(configManager.trunk!!).echo()
	}
}

context(CliktCommand)
private fun error(message: String) {
	echo(message, err = true)
}

context(CliktCommand)
private fun selectBranch(
	text: String,
	default: String? = null,
): String {
	// TODO: Branch picker.
	return prompt(
		text = text,
		default = default,
	) {
		when (it.isBlank()) {
			false -> ConversionResult.Valid(it)
			true -> ConversionResult.Invalid("Cannot be blank.")
		}
	}!!
}

context(CliktCommand)
private fun Remote.requireAuthenticated() {
	if (!isAuthenticated) {
		prompt(
			text = "Please enter a GitHub access token",
			hideInput = true,
		) {
			when {
				it.isBlank() -> ConversionResult.Invalid("Cannot be blank.")
				setToken(it) -> ConversionResult.Valid(it)
				else -> ConversionResult.Invalid("Invalid token.")
			}
		}
	}

	if (repoName == null) {
		error("Unable to parse repository name from origin URL.")
		throw Abort()
	}

	if (!hasRepoAccess) {
		error("Personal token does not have access to $repoName.")
		throw Abort()
	}
}

context(CliktCommand)
private fun VcBranch.submit(
	configManager: ConfigManager,
	remote: Remote,
	vc: VersionControl,
) {
	val target = parent!!.name.let {
		if (it == configManager.trailingTrunk) {
			configManager.trunk!!
		} else {
			it
		}
	}

	val result = remote.openOrRetargetPullRequest(
		branchName = name,
		targetName = target,
	) {
		// TODO: Figure out what to put when there's multiple commits on this branch.
		val info = vc.latestCommitInfo(this)
		Remote.PrInfo(
			title = info.title,
			body = info.body,
		)
	}

	when (result) {
		is Remote.PrResult.Created -> echo("Pull request created: ${result.url}")
		is Remote.PrResult.Updated -> echo("Pull request updated: ${result.url}")
	}
}

private fun VcBranch.flattenStack(): List<VcBranch> {
	return buildList {
		fun VcBranch.addParents() {
			if (!parent!!.isTrunk) {
				parent!!.addParents()
				add(parent!!)
			}
		}

		fun VcBranch.addChildren() {
			children.forEach {
				add(it)
				it.addChildren()
			}
		}

		addParents()
		add(this@flattenStack)
		addChildren()
	}
}

context(CliktCommand)
private fun VcBranch.echo() {
	echo(inset = 0, treeWidth = treeWidth())
}

context(CliktCommand)
private fun VcBranch.echo(
	inset: Int,
	treeWidth: Int,
) {
	children.forEachIndexed { index, child ->
		child.echo(inset + index, treeWidth)
	}

	echo(
		buildString {
			repeat(inset) { append("│ ") }
			append("○")

			val horizontalBranches = (children.size - 1).coerceAtLeast(0)
			if (horizontalBranches > 0) {
				repeat(horizontalBranches - 1) { append("─┴") }
				append("─┘")
			}

			repeat(treeWidth - inset - horizontalBranches - 1) { append("  ") }

			append(" ")
			append(name)
		},
	)
}

private fun VcBranch.treeWidth(): Int {
	return children.sumOf { it.treeWidth() }.coerceAtLeast(1)
}

fun main(args: Array<String>) = Stacker().main(args)
