#!/usr/bin/env python3
"""Loopback-only TCP relay to the local ejabberd upload listener; preserves TLS end to end."""
import argparse
import asyncio
import ipaddress
import subprocess


async def serve(container, port):
    address = subprocess.check_output([
        'docker', 'inspect', '--format',
        '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{"\\n"}}{{end}}', container
    ], text=True).splitlines()[0]
    if not ipaddress.ip_address(address).is_private:
        raise ValueError('Expected a private local container address')
    capacity = asyncio.Semaphore(16)

    async def forward(reader, writer):
        upstream = None
        async with capacity:
            try:
                remote_reader, upstream = await asyncio.wait_for(asyncio.open_connection(address, 5443), 10)

                async def copy(source, destination):
                    while chunk := await asyncio.wait_for(source.read(65536), 60):
                        destination.write(chunk)
                        await destination.drain()

                tasks = [asyncio.create_task(copy(reader, upstream)), asyncio.create_task(copy(remote_reader, writer))]
                try:
                    await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                finally:
                    for task in tasks:
                        task.cancel()
                    await asyncio.gather(*tasks, return_exceptions=True)
            except (OSError, asyncio.TimeoutError):
                pass
            finally:
                writer.close()
                if upstream is not None:
                    upstream.close()

    server = await asyncio.start_server(forward, '127.0.0.1', port, limit=65536)
    print(f'Local encrypted upload relay listening on 127.0.0.1:{port}; no payload logging.', flush=True)
    async with server:
        await server.serve_forever()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--container', default='ejabberd-docker')
    parser.add_argument('--port', type=int, default=5443)
    options = parser.parse_args()
    try:
        asyncio.run(serve(options.container, options.port))
    except KeyboardInterrupt:
        pass
