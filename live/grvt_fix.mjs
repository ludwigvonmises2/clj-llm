import { Grvt, signOrder } from './grvt.mjs'
import { SigningKey, computeAddress } from 'ethers'
const g = new Grvt(process.env.GK, process.env.GPK)
await g.login()
const sub = g.subAccountId
const nonce = Math.floor(Math.random() * 0x7fffffff)
const expirationNanos = Date.now() * 1e6 + 60 * 1e9
const sig = signOrder(process.env.GPK, { subAccountId: sub, isMarket: true, tif: 1,
  postOnly: false, reduceOnly: false, assetIdHex: '0x030601', contractSize: 100000000n,
  limitPrice: 0n, isBuying: true, nonce, expirationNanos })
const body = {
  order: { sub_account_id: sub.toString(), is_market: true,
    time_in_force: 'GOOD_TILL_TIME', post_only: false, reduce_only: false,
    legs: [{ instrument: 'SOL_USDT_Perp', size: '0.1', is_buying_asset: true }],
    signature: { signer: computeAddress(new SigningKey(process.env.GPK).publicKey),
      ...sig, expiration: expirationNanos.toString(), nonce } },
  metadata: { client_order_id: String(nonce) },
}
const r = await fetch('https://trades.testnet.grvt.io/full/v1/create_order', {
  method: 'POST', headers: { 'content-type': 'application/json', cookie: g.cookie, 'x-grvt-account-id': g.accountId },
  body: JSON.stringify(body) })
console.log(JSON.stringify(await r.json()).slice(0, 400))
