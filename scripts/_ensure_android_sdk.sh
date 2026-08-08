#!/bin/bash
# Resolve the Android SDK without depending on the caller's environment.
#
# Sourced by the build scripts. It exists because a build launched any way
# other than from an interactive WSL shell failed with "SDK location not
# found", and the reason is a pair of traps that reinforce each other:
#
#  - `ANDROID_HOME` lives in ~/.bashrc, and Ubuntu's ~/.profile sources
#    ~/.bashrc only for an *interactive* shell. So `bash -lc ./scripts/...`
#    — which is how an agent, a cron job or a nested `wsl.exe` call runs it
#    — starts with no ANDROID_HOME at all, however correct .bashrc is.
#  - local.properties holds a Windows `sdk.dir` (C:/Users/.../Android/Sdk),
#    because the same checkout is also opened from Android Studio on the
#    Windows side. Under WSL that directory does not exist, so the AGP
#    discards it and falls back to ANDROID_HOME — the one thing that was
#    missing.
#
# local.properties is deliberately left alone: it is right for the Windows
# side, and a Windows SDK cannot serve a WSL build anyway (aapt2 and friends
# are .exe). Exporting ANDROID_HOME is enough, since the AGP prefers a valid
# sdk.dir and ignores an invalid one.
#
# A path is only accepted if it looks like an SDK: an empty directory named
# android-sdk would otherwise turn a clear failure into a mystifying one
# several minutes into the build.

ensure_android_sdk() {
    local repo_root="${1:-$PWD}"

    if _android_sdk_looks_real "${ANDROID_HOME:-}"; then
        export ANDROID_SDK_ROOT="$ANDROID_HOME"
        return 0
    fi

    local candidate
    for candidate in \
        "$HOME/android-sdk" \
        "$HOME/Android/Sdk" \
        "/usr/lib/android-sdk" \
        "/opt/android-sdk"
    do
        if _android_sdk_looks_real "$candidate"; then
            export ANDROID_HOME="$candidate"
            export ANDROID_SDK_ROOT="$candidate"
            echo "==> Android SDK: $ANDROID_HOME"
            return 0
        fi
    done

    # A POSIX sdk.dir is worth trying; a Windows one is the trap described
    # above and is reported as such rather than silently ignored.
    local declared
    declared="$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' \
        "$repo_root/local.properties" 2>/dev/null | tail -1)"
    if _android_sdk_looks_real "$declared"; then
        export ANDROID_HOME="$declared"
        export ANDROID_SDK_ROOT="$declared"
        echo "==> Android SDK from local.properties: $ANDROID_HOME"
        return 0
    fi

    echo "ERROR: no usable Android SDK found." >&2
    echo "  ANDROID_HOME=${ANDROID_HOME:-<unset>}" >&2
    [[ -n "$declared" ]] && echo "  local.properties sdk.dir=$declared" >&2
    case "$declared" in
        [A-Za-z]:[/\\]*)
            echo "  That sdk.dir is a Windows path and cannot serve a WSL build." >&2
            echo "  Install the SDK inside WSL, or build from Windows instead." >&2
            ;;
    esac
    echo "  Looked in: \$HOME/android-sdk, \$HOME/Android/Sdk," >&2
    echo "             /usr/lib/android-sdk, /opt/android-sdk" >&2
    return 1
}

# platforms/ and platform-tools/ are what a build actually reaches for, and
# their absence is the difference between an SDK and a directory.
_android_sdk_looks_real() {
    local dir="$1"
    [[ -n "$dir" ]] || return 1
    [[ -d "$dir/platforms" ]] || [[ -d "$dir/platform-tools" ]]
}
