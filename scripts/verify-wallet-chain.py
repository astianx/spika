#!/usr/bin/env python3
"""Independent, read-only chain checks for v0.1 wallet acceptance.

Reads Base through several unrelated public RPC providers. It never uses the app, its Worker, WDK, Candide or a private key, so
a result here is evidence that does not depend on what the app displays.

  verify-wallet-chain.py balances 0xADDRESS
  verify-wallet-chain.py tx 0xBASE_TX_HASH
  verify-wallet-chain.py userop 0xUSEROP_HASH 0xSENDER
"""
import argparse
import json
import sys
import time
import urllib.request

RPCS = ["https://base-rpc.publicnode.com", "https://mainnet.base.org", "https://base.drpc.org"]
USDC = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
USDT = "0xfde4C96c8593536E31F229EA8f37b2ADa2699bb2"
TRANSFER = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
ENTRY_POINTS = ["0x4337084D9E255Ff0702461CF8895CE9E3b5Ff108"]
HEADERS = {"content-type": "application/json", "user-agent": "midori-wallet-verify/1"}


def rpc(url, method, params):
    request = urllib.request.Request(url, json.dumps(
        {"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode(), HEADERS)
    for attempt in range(3):
        try:
            body = json.load(urllib.request.urlopen(request, timeout=30))
            break
        except Exception:
            if attempt == 2:
                raise
            time.sleep(2)
    if "error" in body:
        raise RuntimeError(f"{url} {method}: {body['error']}")
    return body["result"]


def http_json(url):
    return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=30))


def pad(address):
    return "0x" + address[2:].lower().rjust(64, "0")


def balances(address):
    out = {}
    for url in RPCS:
        call = "0x70a08231" + pad(address)[2:]
        out[url] = {
            "block": int(rpc(url, "eth_blockNumber", []), 16),
            "eth_wei": int(rpc(url, "eth_getBalance", [address, "latest"]), 16),
            "usdc_units": int(rpc(url, "eth_call", [{"to": USDC, "data": call}, "latest"]), 16),
            "usdt_units": int(rpc(url, "eth_call", [{"to": USDT, "data": call}, "latest"]), 16),
            "nonce": int(rpc(url, "eth_getTransactionCount", [address, "latest"]), 16),
            "code": rpc(url, "eth_getCode", [address, "latest"]),
        }
    return out


def transfers(receipt):
    found = []
    for log in receipt["logs"]:
        topics = log["topics"]
        if log["address"].lower() in (USDC.lower(), USDT.lower()) and topics and topics[0] == TRANSFER and len(topics) == 3:
            found.append({
                "token": "USDC" if log["address"].lower() == USDC.lower() else "USDT",
                "from": "0x" + topics[1][-40:], "to": "0x" + topics[2][-40:],
                "units": int(log["data"], 16)})
    return found


def base_tx(tx_hash):
    out = {}
    for url in RPCS:
        try:
            out[url] = base_tx_at(url, tx_hash)
        except Exception as error:
            out[url] = {"error": str(error)}
    return out


def base_tx_at(url, tx_hash):
    receipt = rpc(url, "eth_getTransactionReceipt", [tx_hash])
    tip = int(rpc(url, "eth_blockNumber", []), 16)
    if receipt is None:
        return {"found": False, "tip": tip}
    tx = rpc(url, "eth_getTransactionByHash", [tx_hash])
    block = int(receipt["blockNumber"], 16)
    canonical = rpc(url, "eth_getBlockByNumber", [receipt["blockNumber"], False])["hash"]
    return {
        "found": True, "status": int(receipt["status"], 16), "block": block,
        "blockHashMatchesReceipt": canonical == receipt["blockHash"],
        "confirmations": tip - block + 1, "from": tx["from"], "to": tx["to"],
        "valueWei": int(tx["value"], 16), "gasUsed": int(receipt["gasUsed"], 16),
        "transfers": transfers(receipt)}


def userop(op_hash, sender):
    """Find the bundle transaction that carries a UserOperation, without Candide."""
    url = RPCS[0]
    tip = int(rpc(url, "eth_blockNumber", []), 16)
    topics = [None, op_hash, pad(sender)]
    for entry_point in ENTRY_POINTS:
        for span in (2_000, 20_000):
            logs = rpc(url, "eth_getLogs", [{
                "address": entry_point, "fromBlock": hex(max(tip - span, 0)), "toBlock": "latest",
                "topics": topics}])
            if logs:
                return {"entryPoint": entry_point, "transactionHash": logs[0]["transactionHash"],
                        "block": int(logs[0]["blockNumber"], 16), "success": int(logs[0]["data"][2 + 64 * 1:2 + 64 * 2], 16) == 1}
    return {"found": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("balances"); p.add_argument("address")
    p = sub.add_parser("tx"); p.add_argument("hash")
    p = sub.add_parser("userop"); p.add_argument("hash"); p.add_argument("sender")
    args = parser.parse_args()
    if args.cmd == "balances":
        result = {"base": balances(args.address)}
        values = list(result["base"].values())
        result["providersAgree"] = all(
            all(v[k] == values[0][k] for k in ("eth_wei", "usdc_units", "usdt_units", "nonce")) for v in values)
    elif args.cmd == "tx":
        result = base_tx(args.hash)
    else:
        result = userop(args.hash, args.sender)
    json.dump(result, sys.stdout, indent=2)
    print()


if __name__ == "__main__":
    main()
