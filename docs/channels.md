# Channels: Telegram

The second channel after the [web widget](widget.md), and the first one a VPN reseller's
customers actually use. One bot per tenant, from BotFather, talking to the same
`ChatService` as the widget: the same retrieval, the same tools, the same records, the same
deflection number.

## Connecting a bot — the tenant does this

1. In Telegram, talk to **@BotFather**: `/newbot`, a name, a username ending in `bot`. It
   answers with a token, `123456789:ABC…`.
2. In the operations admin, on the tenant's page: paste the token. It is checked against
   Telegram (`getMe`) before it is stored, sealed like a connector token, and shown masked
   from then on. **Test** asks Telegram for the bot's username again.
3. Customers find the bot by its username. `/start` greets in their language; `/new` starts a
   new conversation; anything else is a turn.

```
PUT    /admin/api/tenants/{id}/telegram        {"botToken":"…","mode":"polling"}
GET    /admin/api/tenants/{id}/telegram        -> the bot, token masked; whether it is polling; the webhook URL
POST   /admin/api/tenants/{id}/telegram/test
DELETE /admin/api/tenants/{id}/telegram
```

## Two ways to receive updates

| Mode | How | For |
| --- | --- | --- |
| `polling` | this process asks Telegram for updates (`getUpdates`, long polling, one loop per bot on a virtual thread) | a laptop, a demo, one chat process; needs no public URL |
| `webhook` | Telegram posts each update to `PUBLIC_URL/telegram/{tenant}/{secret}`, registered with `setWebhook` and carrying the secret in `X-Telegram-Bot-Api-Secret-Token` | a deployment with a public origin and any number of chat replicas |

Polling from two processes at once does not work -- Telegram hands the updates to whichever
asked and answers the other with a 409 -- so a deployment with replicas uses webhooks. The
webhook path is outside both the API-key filter and the admin's login: the secret is the
credential, a wrong one is a `404` that says nothing, and the update is handled off the
request thread so Telegram gets its `200` at once and does not retry.

## What a message becomes

A chat is a conversation of the tenant, `tg-<chat id>-<n>`, mapped through the same
`conversation` table as a widget conversation; `/new` increments `n`. The turn is
`ChatService.ask`, blocking: Telegram has no stream, so the bot shows "typing…" and sends the
whole answer, as plain text (Telegram's markdown is its own dialect and a stray asterisk
would swallow a sentence), split at paragraphs when it is longer than one message (4,096
characters). A busy conversation, an exhausted budget and a failed turn are each a sentence
in the chat, in Chinese when the customer wrote Chinese or their Telegram is set to it.

## Who the customer is

Nobody is signed in to a panel in Telegram, but a panel of the V2board family lets a
customer bind their Telegram account in its settings, and with the tenant's admin token and
admin path on the [connector](connectors.md) the panel says which of its users a Telegram
id belongs to (`TelegramIdentity`, `admin/user/fetch` filtered by `telegram_id`). From then
on `lookup_my_subscription` reads that user's account through the admin API, so "how much
traffic do I have left" works in Telegram exactly as it does on the panel's page. The
binding is found once and remembered on the chat (`telegram_chat.panel_user_id`); `/new`
forgets it, so an unbinding is noticed the next time the customer starts over. Unbound, or
on a tenant without admin access to its panel, the tool tells the customer how to bind.

A ticket from Telegram is still one of ours: the panel's admin API cannot raise a ticket
on a user's behalf, only the user's own token can, and Telegram has none.

## Verified

`AdminTelegramApiTest`, against a stand-in Bot API and a stubbed model: a bad token and a
token Telegram refuses are `422`; connected by polling, `/start` is greeted and a question is
answered from the model; `/new` starts a second conversation of the same chat; switching to
webhooks registers the URL with the secret and an update posted there is answered, a wrong
secret or tenant is `404`; support staff see nothing; removal deletes the webhook and the
row, and the audit trail reads connect, switch, remove.

With the tenant's panel stood in as well (`FakeXboard`): a Telegram id the panel has bound
is remembered as its panel user after the first message, forgotten by `/new` and found again;
an unbound one stays unidentified. `XboardAccountLookupTest.throughTheBinding` covers the
lookup by binding and the account read through the admin API.

Not walked against Telegram itself: that needs a bot token from a Telegram account, which is
the tenant's to create. The stand-in speaks the Bot API's shapes for the six methods used.

## Not here, deliberately

- Photos, voice, documents: "text only for now", in both languages.
- Group chats: the bot answers in any chat it is in; a reseller's support group would need
  the bot to be addressed. Private chats are the case that matters.
- WhatsApp and WeChat: each is a channel of the same shape, next to `telegram/`.
