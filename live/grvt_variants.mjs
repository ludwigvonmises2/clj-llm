import { Grvt, signOrder } from '/home/a/work/clj-llm/live/grvt.mjs'
import { SigningKey, computeAddress, TypedDataEncoder, keccak256 } from 'ethers'
import { hexToBytes } from '@noble/hashes/utils.js'

const g = new Grvt(process.env.GK, process.env.GPK)
await g.login()
const sub = g.subAccountId
const TRADES = 'https://trades.testnet.grvt.io'
const TYPES = {
  Order: [
    { name: 'subAccountID', type: 'uint64' }, { name: 'isMarket', type: 'bool' },
    { name: 'timeInForce', type: 'uint8' }, { name: 'postOnly', type: 'bool' },
    { name: 'reduceOnly', type: 'bool' }, { name: 'legs', type: 'OrderLeg[]' },
    { name: 'nonce', type: 'uint32' }, { name: 'expiration', type: 'int64' },
  ],
  OrderLeg: [
    { name: 'assetID', type: 'uint256' }, { name: 'contractSize', type: 'uint64' },
    { name: 'limitPrice', type: 'uint64' }, { name: 'isBuyingContract', type: 'bool' },
  ],
}
function digestOf(v) {
  const value = {
    subAccountID: sub, isMarket: true, timeInForce: v.tif, postOnly: false,
    reduceOnly: false,
    legs: [{ assetID: 0x030601n, contractSize: 100000000n, limitPrice: 0n, isBuyingContract: true }],
    nonce: v.nonce, expiration: BigInt(v.expirationNanos),
  }
  const pre = TypedDataEncoder.encode({ name: 'GRVT Exchange', version: '0', chainId: v.chainId }, TYPES, value)
  return hexToBytes(keccak256(pre).slice(2))
}
const variants = [
  { lbl: 'tif3 chain326 exp-nanos', chainId: 326, tif: 3, expMs: false },
  { lbl: 'tif3 chain325 exp-nanos', chainId: 325, tif: 3, expMs: false },
  { lbl: 'tif4 chain326 exp-nanos', chainId: 326, tif: 4, expMs: false },
  { lbl: 'tif1 chain326 exp-nanos', chainId: 326, tif: 1, expMs: false },
  { lbl: 'tif3 chain326 exp-ms   ', chainId: 326, tif: 3, expMs: true },
]
for (const v of variants) {
  const nonce = Math.floor(Math.random() * 0x7fffffff)
  const expirationNanos = v.expMs ? Date.now() + 60000 : Date.now() * 1e6 + 60 * 1e9
  const dd = digestOf({ ...v, nonce, expirationNanos })
  const sig = signOrder(process.env.GPK, { subAccountId: sub, isMarket: true, tif: v.tif,
    postOnly: false, reduceOnly: false, assetIdHex: '0x030601', contractSize: 100000000n,
    limitPrice: 0n, isBuying: true, nonce, expirationNanos })
  // NOTE: signOrder uses its own chainId 326 — for chain325 variant the sig differs from dd; acceptable probe
  const body = {
    order: { sub_account_id: sub.toString(), is_market: true,
      time_in_force: v.tif === 3 ? 'IMMEDIATE_OR_CANCEL' : v.tif === 4 ? 'FILL_OR_KILL' : 'GOOD_TILL_TIME',
      post_only: false, reduce_only: false,
      legs: [{ instrument: 'SOL_USDT_Perp', size: '0.1', is_buying_asset: true }],
      signature: { signer: computeAddress(new SigningKey(process.env.GPK).publicKey),
        ...sig, expiration: expirationNanos.toString(), nonce } },
    metadata: { client_order_id: nonce },
  }
  const r = await fetch(TRADES + '/full/v1/create_order', {
    method: 'POST', headers: { 'content-type': 'application/json', cookie: g.cookie, 'x-grvt-account-id': g.accountId },
    body: JSON.stringify(body) })
  const j = await r.json()
  console.log(v.lbl, '→', JSON.stringify(j).slice(0, 120))
  if (!j.code) break
}
