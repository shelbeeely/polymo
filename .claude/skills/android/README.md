# .claude/skills/android/ — vendored copy of Google's Android Skills

This is a checked-in copy of [github.com/android/skills](https://github.com/android/skills)
(the AI-optimized instructions described at
[developer.android.com/tools/agents/android-skills](https://developer.android.com/tools/agents/android-skills)),
vendored at upstream commit `bac232fd02b0855df9275281a2a7a47643768719`
(2026-09-07). It is not written here — treat every `SKILL.md` and its
`scripts/`, `references/`, `assets/` as upstream content, not project docs.

Claude Code discovers project skills under `.claude/skills/`, so these
activate automatically in this repo when a task matches one (migrating
XML to Compose, upgrading AGP, edge-to-edge, R8 auditing, and so on) —
no `android` CLI or Android Studio needed to use them here.

```
build-system/agp/agp-9-upgrade
camera/camerax
device-ai/appfunctions
device-ai/ml-kit-genai-prompt-api
devtools/android-cli
identity/restore-credentials
identity/verified-email
jetpack-compose/adaptive
jetpack-compose/migration/migrate-xml-views-to-jetpack-compose
jetpack-compose/theming/styles
media/media3-cast-integration
navigation/navigation-3
navigation/navigation-event
performance/r8-analyzer
play/engage-sdk-integration
play/play-billing-library-version-upgrade
play/play-policy-insights
profilers/android-profiler
security/android-intent-security
system/edge-to-edge
testing/testing-setup
tv/leanback-to-compose-tv-migration
wear/wear-compose-m3
xr/display-glasses-with-jetpack-compose-glimmer
```

The full set was vendored rather than a curated subset (this app has no
camera, media-cast, Wear, TV or XR surface today) so that updating later
is a straight re-copy from upstream, not a re-review of what to include.

## Keeping it current

There's no sync test for this directory — unlike `design-system/`, nothing
in this repo's build depends on these files matching upstream, so drift
here fails silently. To refresh:

```bash
GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 https://github.com/android/skills /tmp/android-skills
rm -rf .claude/skills/android/{build-system,camera,device-ai,devtools,identity,jetpack-compose,media,navigation,performance,play,profilers,security,system,testing,tv,wear,xr,LICENSE.txt}
cp -r /tmp/android-skills/{build-system,camera,device-ai,devtools,identity,jetpack-compose,media,navigation,performance,play,profilers,security,system,testing,tv,wear,xr,LICENSE.txt} .claude/skills/android/
```

then update the commit hash/date above and commit. If you customize a
skill in place, rename its directory first — upstream's own convention —
so a refresh doesn't silently overwrite your changes.

Licensed under Apache License 2.0 (`LICENSE.txt`), same as upstream.
