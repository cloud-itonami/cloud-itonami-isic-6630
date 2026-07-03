# cloud-itonami-6630

Open Business Blueprint for **ISIC Rev.5 6630**: Fund management activities.

This repository designs a forkable OSS business for fund management activities -- managing collective investment vehicles (mutual funds, pension-fund assets, etc.) on behalf of unitholders -- run by a qualified, licensed operator so a community or
independent professional never surrenders customer data and ledgers to a
closed SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a document-custody robot manages physical mandate/prospectus custody,
under an actor that proposes actions and an independent **Fund Management Governor**
that gates them. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions require human sign-off.

## Core Contract

```text
intake + identity + case/account records
        |
        v
FundManager-LLM -> Fund Management Governor -> hold, proceed, or human approval
        |
        v
case/account ledger + evidence record + audit
```

No automated proposal, by itself, can complete the following without governor
approval and audit evidence: executing a rebalancing trade or drawing a management fee.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6630`). Required capabilities are implemented by:

- [`kotoba-lang/securities`](https://github.com/kotoba-lang/securities)
  -- position, trade, fund-NAV and mandate contracts

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Maturity

`:blueprint` -- this repository is the published business/operator design.
The governed actor implementation (`FundManager-LLM` + `Fund Management Governor` as
running code) is a follow-up, same as any other `:blueprint`-tier
`cloud-itonami-*` entry in `kotoba-lang/industry`'s registry.

## License

Code and implementation templates are AGPL-3.0-or-later.
