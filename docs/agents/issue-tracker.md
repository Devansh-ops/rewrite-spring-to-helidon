# Issue tracker: GitHub

Issues and specs for this repo live as GitHub issues. Use the `gh` CLI for all operations.

## Conventions

- Create issues with `gh issue create`.
- Read issues and comments with `gh issue view <number> --comments`.
- List issues with `gh issue list`.
- Comment with `gh issue comment`.
- Manage labels with `gh issue edit`.
- Close issues with `gh issue close`.

Infer the repository from `git remote -v`; `gh` does this automatically inside the clone.

## Pull requests as a triage surface

**PRs as a request surface: no.**

GitHub shares one number space across issues and pull requests. Resolve an ambiguous
reference with `gh pr view <number>`, falling back to `gh issue view <number>`.

## When a skill says “publish to the issue tracker”

Create a GitHub issue.

## When a skill says “fetch the relevant ticket”

Run `gh issue view <number> --comments`.

## Dependencies

Use GitHub’s native issue dependencies when available. Otherwise, record dependencies using
a `Blocked by: #<number>` line at the top of an issue.

A ticket is ready for implementation only when all blocking issues are closed.
