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

The customer is not signed in to any panel in Telegram, so `lookup_my_subscription` answers
"sign in to the panel and ask there", and a ticket is one of ours rather than the panel's.
The next step for the reseller segment is identifying the Telegram user through the panel's
own binding (Xboard keeps `telegram_id` on the user) with the tenant's admin token, which
would make the subscription tool and panel tickets work from Telegram too.

## Verified

`AdminTelegramApiTest`, against a stand-in Bot API and a stubbed model: a bad token and a
token Telegram refuses are `422`; connected by polling, `/start` is greeted and a question is
answered from the model; `/new` starts a second conversation of the same chat; switching to
webhooks registers the URL with the secret and an update posted there is answered, a wrong
secret or tenant is `404`; support staff see nothing; removal deletes the webhook and the
row, and the audit trail reads connect, switch, remove.

Not walked against Telegram itself: that needs a bot token from a Telegram account, which is
the tenant's to create. The stand-in speaks the Bot API's shapes for the six methods used.

## Not here, deliberately

- Identifying the customer (above).
- Photos, voice, documents: "text only for now", in both languages.
- Group chats: the bot answers in any chat it is in; a reseller's support group would need
  the bot to be addressed. Private chats are the case that matters.
- WhatsApp and WeChat: each is a channel of the same shape, next to `telegram/`.
