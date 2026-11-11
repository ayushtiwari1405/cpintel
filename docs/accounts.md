# Accounts, roles and passwords

## Roles and accounts

Three tiers:

| Role | Can do |
|---|---|
| `USER` | The product. No console. |
| `ADMIN` | The console: accounts, groups, audit trail, contest file rules for the contests their classrooms run (the deployment-wide default is the super admin's). Can activate and deactivate accounts, and **create ordinary users** — singly or by roster import. |
| `SUPER_ADMIN` | Everything an admin can, **plus** assigning roles and creating other admins. |

Role assignment is deliberately kept out of `ADMIN`, and so is creating an `ADMIN`. An admin who
can promote other admins can promote themselves past any limit placed on them — and one who
could *create* an admin could create one for themselves and hold two accounts, which is the same
escalation spelled differently. So the tier that hands out privilege sits above the tier that
uses it.

Creating an ordinary user is not that, and is an admin's job: somebody running a contest has to
be able to add the people sitting it, and routing every new participant through a super admin
turns a class list into an escalation request. The same applies to roster imports, which are
safe for a different and more specific reason — **every account an import creates is a `USER`,
with no way for a row to say otherwise**. If that ever changes, the permission has to move back.

All of it is enforced server-side — the UI hides what you cannot use, but a hand-written request
from an ordinary admin trying to create an admin gets a 403, not an account.

**Self-registration is off by default.** There is no sign-up page; accounts are created by an
admin from `/admin/users` or by roster import. Set `CPINTEL_REGISTRATION_ENABLED=true` to reopen public
sign-up, and the `/api/v1/auth/register` endpoint starts accepting again.

**An admin may not touch another admin's account.** Editing details, deactivating, signing out
and setting a password are all refused for a target who holds `ADMIN` or `SUPER_ADMIN` unless
the caller is a super admin. An admin who can deactivate or re-password a peer can remove the
person who would have stopped them, and one who could reach a super admin could remove the tier
that limits them at all — which is the same escalation the role rule exists to prevent, arrived
at from a different screen. A super admin has no such limit: every account in the deployment,
admins included, is theirs to change.

**Deleting an account is a super admin's, and is the blunt instrument.** The cascade takes the
person's submissions, files, standings and examination events with them, so it is refused for
anybody who has sat an examination — their session log is evidence about a paper somebody may
still be marking. Deactivating stops them signing in and leaves all of it readable, which is
what "remove this person" almost always means.

## Passwords

**The first password is handed over, not emailed.** An account is created with a username and a
password, and both are given to the person directly — on a deployment sheet, in a room, however
that particular arrangement works. Nothing is mailed to bootstrap an account, because the mail
would be a live credential sitting in a mailbox and because the networks this runs on cannot
always reach a mail server at the moment the accounts are made.

**After that the password is the owner's.** They can change it from `/profile`, which asks for
the current one — a session can be an unlocked laptop, and that field is what makes the person
at the keyboard the owner rather than whoever sat down after them. If they cannot sign in at
all, **Forgot password?** on the sign-in page mails a link to the address on the account.

The admin user list has a **Password** column showing whether each account still has the
password it was issued. Until somebody changes their own, the administrator who created it
knows it, and a column of "as issued" is a room that has not been asked to change theirs.

A password its owner sets (registering, resetting, or changing it from the profile) has to be
strong: at least 10 characters with a lower-case letter, an upper-case letter, a digit and a
symbol, not a common password even when decorated (`P@ssw0rd!2026` is refused), and not built on
the account's username or email address. The server enforces this (`PasswordPolicy`); the forms
tick the rules off as they are typed. Passwords an administrator sets are not checked — they are
usually generated, and the owner is asked to replace them.

A reset link is stored only as a SHA-256 digest, works once, expires after 5 minutes, and
redeeming it signs every device out — because somebody resetting a password they believe is
known to somebody else has not finished until the other party is signed out. Asking for a new
link retires the previous one, so a mailbox with four reset mails in it holds one working link.
At most one mail a minute goes to an account: a request within a minute of a link that still
works sends nothing (the answer is the same, and that link keeps working), because anybody can
type somebody else's address into the form.

`/auth/forgot-password` answers identically whether or not the address belongs to an account.
The endpoint is open to the internet, and an answer that distinguished the two would be a way
to test a list of addresses against the roster of whoever is being examined.

**Signing in takes either a username or an email address**, in one field. People here are given
a username and may never use the address on their account, so insisting on the address would
ask half of them for something they do not have to hand.

**With no SMTP configured, resets can still work — for an operator who opts in.** By default
the backend logs only that a message was not sent, with the subject and the recipient masked
(`a***@example.edu`): a reset link signs in as its recipient for an hour, and logs are read by
more people than should hold one. Set `CPINTEL_MAIL_LOG_UNDELIVERED=true` and the whole message,
recipient and link included, is written to the backend log at INFO, so a deployment on a closed
network can complete a reset by hand; anyone who can read that log can then use those links.
The backend warns at startup either way rather than refusing, because an exam lab with no mail
server is a real arrangement rather than a mistake. An administrator can also set a password
directly from `/admin/users`, which shows it once and never emails it.

### The first super admin

Nothing inside the product can create one — that is the point, since it would make the top of
the permission tree reachable from a screen. It is named in the environment instead:

```bash
CPINTEL_ADMIN_EMAIL=you@example.com
# only needed if no account has that address yet
CPINTEL_ADMIN_PASSWORD=something-long-and-yours
```

On the next start, an existing account with that address is promoted to `SUPER_ADMIN`; if none
exists, one is created — but only when a password is also supplied. An ordinary account is
promoted only if its owner made it and has verified the address; one created by staff, or still
unverified, is refused with an error in the backend log. Otherwise an admin could create an
account on the configured address whenever it was free and be made super admin at the next
restart. An admin-level account is promoted either way, since only a super admin can make one. There is deliberately no
default administrator password anywhere in this deployment, because a well-known one is the
most reliable way to end up compromised.

It is idempotent and safe to leave set. It is also the way back in if the last admin is ever
locked out, since it reactivates the named account as well as promoting it. A super admin
cannot be demoted or deactivated from the console, so moving that role means changing this
setting and restarting.
