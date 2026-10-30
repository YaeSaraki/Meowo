#!/bin/sh
#
# The release notes for a Meowo release, written from the commits between two tags.
#
# ### Why they are generated rather than written
#
# A hand-written changelog is a promise that somebody remembered. The commits are the changelog:
# they are already written, already reviewed, and they cannot drift away from what was actually
# released. A release built from a tag therefore describes exactly the commits that tag contains.
#
# The grouping follows the commit convention the repository already uses — `feat(scope): …`,
# `fix(scope): …`, `build(gate): …` — so the notes read in the same vocabulary the history does.
#
# ### Usage
#
#   scripts/release-notes.sh <version> [previous-tag]
#
# Writes the notes to stdout. The caller redirects them into the release.

set -eu

version="${1:?usage: release-notes.sh <version> [previous-tag]}"
previous="${2:-}"

# The slug is only used to link each commit, so the notes still make sense without it.
repo="${GITHUB_REPOSITORY:-}"

if [ -n "$previous" ]; then
    range="$previous..HEAD"
else
    # The first release: everything the repository has.
    range="HEAD"
fi

# The full hash, then the subject. Splitting on the first space is enough: a hash has no spaces
# in it and a subject cannot contain a newline (`%s` is the first line of the message only).
git log --no-merges --pretty=format:'%H %s' "$range" |
awk -v repo="$repo" -v version="$version" -v first="$previous" '
    # The bucket a commit type belongs to.
    function bucket(t) {
        if (t == "feat")     return "新增功能"
        if (t == "fix")      return "修复"
        if (t == "perf")     return "性能"
        if (t == "refactor") return "重构"
        if (t == "build")    return "构建"
        if (t == "ci")       return "持续集成"
        if (t == "docs")     return "文档"
        if (t == "test")     return "测试"
        if (t == "chore")    return "杂务"
        return "其它"
    }
    # The order the buckets are printed in. A fixed order beats the order the commits happen to
    # come in: the reader wants to see the features first, not whichever type was committed last.
    function order(b) {
        if (b == "新增功能")   return 1
        if (b == "修复")       return 2
        if (b == "性能")       return 3
        if (b == "重构")       return 4
        if (b == "构建")       return 5
        if (b == "持续集成")   return 6
        if (b == "文档")       return 7
        if (b == "测试")       return 8
        if (b == "杂务")       return 9
        return 10
    }
    BEGIN {
        print "## Meowo " version
        print ""
        print "**运行要求**：Paper 26.2（build 129）· Java 25 · PacketEvents 2.14.0（仅 render 需要，可不装）"
        print ""
        # The upgrade note, because the merge is permanent behaviour and not every operator knows
        # it: the files only ever **gain** keys, and a new `ui.yml` layout waits for a restart.
        print "**升级须知**：`config.yml` 与 `ui.yml` 只会**补上**新键，你原来的值一律不动；"
        print "`ui.yml` 的新布局**重启后**才生效。"
        print ""
    }
    {
        count++
        sp = index($0, " ")
        full = substr($0, 1, sp - 1)
        hash = substr(full, 1, 7)
        subject = substr($0, sp + 1)

        # `type(scope): text` — the scope is optional, the text is the rest of the line.
        head = ""
        text = subject
        p = index(subject, ": ")
        if (p > 0) {
            head = substr(subject, 1, p - 1)
            text = substr(subject, p + 2)
        }
        type = head
        scope = ""
        lp = index(head, "(")
        if (lp > 0) {
            type = substr(head, 1, lp - 1)
            scope = substr(head, lp + 1, length(head) - lp - 1)
        }

        b = bucket(type)
        n = order(b)
        if (!(b in seen)) { seen[b] = 1; names[n] = b }
        line = "- " text
        if (scope != "") line = line "（" scope "）"
        if (repo != "") {
            line = line " — [" hash "](" "https://github.com/" repo "/commit/" full ")"
        } else {
            line = line " — " hash
        }
        lines[n] = lines[n] "\n" line
    }
    END {
        if (count == 0) {
            print "本次发布没有新的提交。"
            exit
        }
        if (first == "") {
            print "首次发布。以下是这个仓库的全部提交："
            print ""
        }
        for (i = 1; i <= 10; i++) {
            if (i in names) {
                print "### " names[i]
                print lines[i]
                print ""
            }
        }
    }
'
