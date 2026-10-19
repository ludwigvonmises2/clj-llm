// GRVT testnet live client: API-key login (cookie), EIP-712 signed
// market orders, REST ticker. Signing mirrors f-trader's GrvtSigner.fs
// (domain "GRVT Exchange" v"0", chainId 326 testnet; Order{subAccountID
// uint64, isMarket, timeInForce uint8, postOnly, reduceOnly, legs
// OrderLeg[]{assetID uint256, contractSize uint64, limitPrice uint64,
// isBuyingContract}, nonce uint32, expiration int64-nanos}).
// Contract/price units: venue decimals (base 1e9, quote 1e6).
import { keccak_256 } from '@noble/hashes/sha3.js'
import { utf8ToBytes, hexToBytes, bytesToHex, concatBytes } from '@noble/hashes/utils.js'
import { SigningKey, computeAddress, TypedDataEncoder, keccak256 } from 'ethers'

const EDGE = 'https://edge.testnet.grvt.io'
const TRADES = 'https://trades.testnet.grvt.io'
const MD = 'https://market-data.testnet.grvt.io'
const CHAIN_ID = 326n

export const INSTRUMENTS = {
  ETH_USDT_Perp: { hash: '0x030401', baseDec: 9n, quoteDec: 6n, tick: '0.01', minSize: '0.001', minNotional: 20 },
  SOL_USDT_Perp: { hash: '0x030601', baseDec: 9n, quoteDec: 6n, tick: '0.01', minSize: '0.01', minNotional: 5 },
  BTC_USDT_Perp: { hash: '0x030501', baseDec: 9n, quoteDec: 6n, tick: '0.1', minSize: '0.001', minNotional: 100 },
}

// ---- EIP-712 (typed encoder; mirrors f-trader GrvtSigner.fs) --------------
const TYPES = {
  Order: [
    { name: 'subAccountID', type: 'uint64' },
    { name: 'isMarket', type: 'bool' },
    { name: 'timeInForce', type: 'uint8' },
    { name: 'postOnly', type: 'bool' },
    { name: 'reduceOnly', type: 'bool' },
    { name: 'legs', type: 'OrderLeg[]' },
    { name: 'nonce', type: 'uint32' },
    { name: 'expiration', type: 'int64' },
  ],
  OrderLeg: [
    { name: 'assetID', type: 'uint256' },
    { name: 'contractSize', type: 'uint64' },
    { name: 'limitPrice', type: 'uint64' },
    { name: 'isBuyingContract', type: 'bool' },
  ],
}
const DOMAIN = { name: 'GRVT Exchange', version: '0', chainId: 326 }

function orderDigest(p) {
  const value = {
    subAccountID: p.subAccountId,
    isMarket: p.isMarket,
    timeInForce: p.tif,
    postOnly: p.postOnly,
    reduceOnly: p.reduceOnly,
    legs: [{ assetID: BigInt(p.assetIdHex), contractSize: p.contractSize,
             limitPrice: p.limitPrice, isBuyingContract: p.isBuying }],
    nonce: p.nonce,
    expiration: BigInt(p.expirationNanos),
  }
  const enc = TypedDataEncoder.encode(DOMAIN, TYPES, value)
  return hexToBytes(keccak256(enc).slice(2))  // enc may be hash or pre-image: keccak idempotent on hash? no — see below
}

export function signOrder(privateKeyHex, p) {
  const digest = orderDigest(p)
  const eth = new SigningKey(privateKeyHex).sign('0x' + bytesToHex(digest))
  return { r: eth.r, s: eth.s, v: eth.yParity + 27 }
}

// ---- session ----------------------------------------------------------------
export class Grvt {
  constructor(apiKey, privateKeyHex, accountId) { this.apiKey = apiKey; this.pk = privateKeyHex; this.accountId = accountId; this.cookie = null; this.subAccountId = null }
  async login() {
    const r = await fetch(`${EDGE}/auth/api_key/login`, {
      method: 'POST', headers: { 'content-type': 'application/json', cookie: 'rm=true;' },
      body: JSON.stringify({ api_key: this.apiKey }),
    })
    const setc = r.headers.get('set-cookie') || ''
    this.cookie = (setc.match(/gravity=[^;]+/) || [])[0]
    this.accountId = r.headers.get('x-grvt-account-id') || this.accountId
    const body = await r.json()
    this.subAccountId = BigInt(body.sub_account_id)
    if (!this.cookie) throw new Error('grvt login failed')
    return this
  }
  async postUrl(url, payload) {
    const r = await fetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json', cookie: this.cookie, 'x-grvt-account-id': this.accountId },
      body: JSON.stringify(payload ?? {}),
    })
    const j = await r.json().catch(() => ({ status: 'http_' + r.status }))
    return j
  }
  async post(path, payload) {
    const r = await fetch(`${EDGE}${path}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', cookie: this.cookie, 'x-grvt-account-id': this.accountId },
      body: JSON.stringify(payload ?? {}),
    })
    const j = await r.json()
    if (j.status && j.status !== 'success' && j.code) throw new Error(`${path}: ${j.message || j.code}`)
    return j
  }
  async ticker(instrument) {
    const r = await fetch(`${MD}/full/v1/ticker`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ instrument }),
    })
    return r.json()
  }
  async marketOrder(instrumentName, sizeHuman, isBuying) {
    const inst = INSTRUMENTS[instrumentName]
    const ticker = await this.ticker(instrumentName)
    const ref = Number(isBuying ? ticker.result.ask_price : ticker.result.bid_price)
    // contractSize in base units (1e9); limitPrice 0 for market orders
    const contractSize = BigInt(Math.round(sizeHuman * Number(10n ** inst.baseDec)))
    const nonce = (Math.floor(Math.random() * 0xffffffff)) >>> 0
    const expirationNanos = Date.now() * 1e6 + 60 * 1e9 // +60s in nanos
    const sig = signOrder(this.pk, {
      subAccountId: this.subAccountId, isMarket: true, tif: 3, // IMMEDIATE_OR_CANCEL (f-trader GrvtTimeInForce)
      postOnly: false, reduceOnly: false,
      assetIdHex: inst.hash, contractSize, limitPrice: 0n,
      isBuying, nonce, expirationNanos,
    })
    const body = {
      order: {
        sub_account_id: this.subAccountId.toString(),
        is_market: true,
        time_in_force: 'IMMEDIATE_OR_CANCEL',
        post_only: false,
        reduce_only: false,
        legs: [{ instrument: instrumentName, size: sizeHuman.toString(), is_buying_asset: isBuying }],
        signature: { signer: computeAddress(new SigningKey(this.pk).publicKey), ...sig, expiration: expirationNanos.toString(), nonce },
      },
      metadata: { client_order_id: nonce },
    }
    return this.postUrl(TRADES + "/full/v1/create_order", body)
  }
}

