<!--
One topic per pull request. CONTRIBUTING.md has the rules; the short version:
behave like Android, no app-specific code, Windows code in windows/, and a
runtime/ change is listed in windows/README.md.
-->

## What

<!-- What changes, in a few sentences. Link the issue it fixes: Fixes #123. -->

## Why

<!-- The problem, and why this is the right fix (what AOSP does, what the app expects). -->

## How it was tested

<!--
Which apps you ran it on and what you saw. For anything visible, a screenshot
before and after. For a
runtime change, the app and version, and the lines of last-run.log that show
the fix working.
-->

- Apps tested:
- Screenshot:

## Checklist

- [ ] CI (`win-ci.yml`) is green
- [ ] A change under `runtime/` is listed in `windows/README.md`
- [ ] `windows/package/RELEASE_NOTES.md` mentions it, if a user would notice it
