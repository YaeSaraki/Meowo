# Triage Labels

The skills speak in terms of five canonical triage roles. This file maps those roles to the actual label strings used in this repo's issue tracker.

| Label in mattpocock/skills | Label in our tracker | Meaning                                  |
| -------------------------- | -------------------- | ---------------------------------------- |
| `needs-triage`             | `needs-triage`       | Maintainer needs to evaluate this issue  |
| `needs-info`               | `needs-info`         | Waiting on reporter for more information |
| `ready-for-agent`          | `ready-for-agent`    | Fully specified, ready for an AFK agent  |
| `ready-for-human`          | `ready-for-human`    | Requires human implementation            |
| `wontfix`                  | `wontfix`            | Will not be actioned                     |

When a skill mentions a role (e.g. "apply the AFK-ready triage label"), use the corresponding label string from this table.

Edit the right-hand column to match whatever vocabulary you actually use.

## Category labels

`/triage` also applies one **category** label per issue, alongside the state label above. The categories are `bug` and `enhancement`:

| Category in mattpocock/skills | Label in our tracker |
| ----------------------------- | -------------------- |
| `bug`                         | `bug`                |
| `enhancement`                 | `enhancement`        |

Every triaged issue carries exactly one category label and one state label. If the two kinds conflict (e.g. `wontfix` plus `ready-for-agent`), flag it and ask the maintainer before doing anything else.
