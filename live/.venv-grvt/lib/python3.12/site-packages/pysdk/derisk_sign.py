import time
import random
import json
import requests
from eth_account import Account
from eth_account.messages import encode_typed_data

def generate_payload(sub_account_id: int, ratio: str, private_key_hex: str):
    derisk_ratio_int = int(float(ratio) * 1_000_000)
    expiration_ns = int((time.time() + 86400) * 1_000_000_000)
    nonce = random.randint(1, 2**32 - 1)

    domain_data = {
        "name": "GRVT Exchange",
        "version": "0",
        "chainId": 327,
    }

    types = {
        "SetDeriskToMaintenanceMarginRatio": [
            {"name": "subAccountID", "type": "uint64"},
            {"name": "deriskToMaintenanceMarginRatio", "type": "uint32"},
            {"name": "nonce", "type": "uint32"},
            {"name": "expiration", "type": "int64"},
        ]
    }

    signature_payload = {
        "subAccountID": sub_account_id,
        "deriskToMaintenanceMarginRatio": derisk_ratio_int,
        "nonce": nonce,
        "expiration": expiration_ns,
    }

    message = encode_typed_data(domain_data, types, signature_payload)
    signed = Account.sign_message(message, private_key_hex)
    signer = Account.from_key(private_key_hex)

    return {
        "sub_account_id": str(sub_account_id),
        "ratio": ratio,
        "signature": {
            "signer": signer.address.lower(),
            "r": hex(signed.r),
            "s": hex(signed.s),
            "v": signed.v,
            "expiration": str(expiration_ns),
            "nonce": nonce,
        }
    }

def send_set_derisk_ratio(payload: dict, cookie: str):
    url = "https://trades.dev.gravitymarkets.io/full/v1/set_derisk_mm_ratio"
    headers = {
        "Content-Type": "application/json",
        "Cookie": f"gravity={cookie}"
    }

    response = requests.post(url, headers=headers, json=payload)

    print("\n=== Sent Payload ===")
    print(json.dumps(payload, indent=2))
    print("\n=== Server Response ===")
    print(f"Status Code: {response.status_code}")
    print(response.text)
    return response.ok

def query_account_summary(sub_account_id: str, cookie: str):
    url = "https://trades.dev.gravitymarkets.io/full/v1/account_summary"
    headers = {
        "Content-Type": "application/json",
        "Cookie": f"gravity={cookie}"
    }

    payload = {
        "sub_account_id": sub_account_id
    }

    response = requests.post(url, headers=headers, json=payload)

    if response.status_code == 200:
        data = response.json()
        result = data.get("result", {})
        derisk_margin = result.get("derisk_margin")
        derisk_ratio = result.get("derisk_to_maintenance_margin_ratio")
        print("\n=== Account Summary ===")
        print(f"derisk_margin: {derisk_margin}")
        print(f"derisk_to_maintenance_margin_ratio: {derisk_ratio}")
    else:
        print("\nFailed to query account summary.")
        print(f"Status Code: {response.status_code}")
        print(response.text)

if __name__ == "__main__":
    # 🔧 USER INPUTS
    sub_account_id = 7905937035743223
    ratio = "2.0"
    private_key = "0x0e0409aa8b1e03e7a04db2bb7..."
    cookie = "DGWA7XKR64UPI5I5IPRAP6OGB5UB4M5SV77..."

    # Step 1: Generate signed payload and send to set_derisk_mm_ratio
    payload = generate_payload(sub_account_id, ratio, private_key)
    success = send_set_derisk_ratio(payload, cookie)

    # Step 2: If success, query account summary
    if success:
        query_account_summary(str(sub_account_id), cookie)