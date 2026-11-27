"""Experimental browser-free Xbox RTC player. Auth stays in WII-UU's Node service."""
import asyncio
import json
import os
import re
import struct
import sys
import time
import uuid

BUTTONS = ("Nexus", "Menu", "View", "A", "B", "X", "Y", "DPadUp",
           "DPadDown", "DPadLeft", "DPadRight", "LeftShoulder",
           "RightShoulder", "LeftThumb", "RightThumb")
CHANNELS = (("control", "controlV1"), ("input", "1.0"),
            ("message", "messageV1"), ("chat", "chatV1"))
TERMINAL = {"Failed", "Stopped", "Closed", "Ended", "Terminated", "Cancelled"}
WIDTH, HEIGHT, FPS = 1280, 720, 30


def header(kind, sequence, now):
    return struct.pack("<HId", kind, sequence & 0xffffffff, now)


def pad_packet(sequence, frames, now):
    data = bytearray(header(2, sequence, now))
    data.append(len(frames))
    for slot, frame in sorted(frames.items()):
        mask = sum(1 << (i + 1) for i, name in enumerate(BUTTONS)
                   if frame.get(name, 0) > .5)
        def axis(name, sign=1):
            return int(max(-1, min(1, frame.get(name, 0) * sign)) * 32767)
        def trigger(name):
            return int(max(0, min(1, frame.get(name, 0))) * 65535)
        data.extend(struct.pack("<BHhhhhHHI", slot, mask,
                                axis("LeftThumbXAxis"), axis("LeftThumbYAxis", -1),
                                axis("RightThumbXAxis"), axis("RightThumbYAxis", -1),
                                trigger("LeftTrigger"), trigger("RightTrigger"), 1))
        data.extend(struct.pack(">I", 1))
    return bytes(data)


def local_ice(sdp):
    candidates, index, mid = [], -1, None
    for line in sdp.splitlines():
        if line.startswith("m="):
            index += 1
            mid = None
        elif line.startswith("a=mid:"):
            mid = line[6:]
        elif line.startswith("a=candidate:") and index >= 0:
            candidates.append(dict(candidate=line[2:], sdpMid=mid, sdpMLineIndex=index))
    return candidates


def unwrap(value):
    if isinstance(value, dict) and isinstance(value.get("exchangeResponse"), str):
        return json.loads(value["exchangeResponse"])
    return value


def remote_ice(value):
    value = unwrap(value)
    if isinstance(value, dict):
        value = value.get("iceCandidates") or value.get("candidates")
    if not isinstance(value, list):
        return []
    return [x for x in value if isinstance(x, dict)
            and isinstance(x.get("candidate"), str)
            and x["candidate"].strip().removeprefix("a=").startswith("candidate:")
            and (isinstance(x.get("sdpMid"), str)
                 or isinstance(x.get("sdpMLineIndex"), int))]


def strip_ice(sdp):
    return "\r\n".join(line for line in sdp.splitlines()
                       if not line.startswith(("a=candidate:", "a=end-of-candidates"))) + "\r\n"


def channel_json(channel, payload):
    if channel.readyState == "open":
        channel.send(json.dumps(payload, separators=(",", ":")))


def envelope(target, content):
    return dict(type="Message", id=str(uuid.uuid4()), cv="", target=target,
                content=json.dumps(content, separators=(",", ":")))


async def run():
    import aiohttp
    import av
    import pygame
    from aiortc import RTCPeerConnection, RTCSessionDescription, RTCRtpReceiver
    from aiortc.sdp import candidate_from_sdp

    port = int(os.environ.pop("WIIUU_RTC_PORT"))
    secret = os.environ.pop("WIIUU_RTC_SECRET")
    title = os.environ.pop("WIIUU_RTC_TITLE")
    auto_audio = os.environ.pop("WIIUU_RTC_AUDIO", "1") == "1"
    if not 0 < port < 65536 or not re.fullmatch("[a-f0-9]{64}", secret):
        raise ValueError("Invalid launcher")
    pygame.display.init()
    pygame.font.init()
    pygame.display.set_caption("WII-UU Xbox Cloud Player")
    screen = pygame.display.set_mode((WIDTH, HEIGHT), pygame.RESIZABLE)
    font = pygame.font.Font(None, 32)
    audio = None
    try:
        pygame.mixer.init(frequency=48000, size=-16, channels=2, buffer=1024)
        audio = pygame.mixer.Channel(0)
    except pygame.error:
        print("Audio output unavailable", file=sys.stderr)
    pc = RTCPeerConnection()
    closing = asyncio.Event()
    tasks = []
    latest = None
    muted = not auto_audio
    status = "Requesting Xbox session..."
    started = False
    handshake = False
    sequence = 0
    epoch = time.monotonic()
    def now():
        return (time.monotonic() - epoch) * 1000
    async with aiohttp.ClientSession(
            base_url="http://127.0.0.1:" + str(port),
            headers={"X-Wiiuu-Session": secret}, trust_env=False,
            timeout=aiohttp.ClientTimeout(total=35)) as session:
        async def request(action, data=None):
            async with session.request("GET" if data is None else "POST",
                                       "/api/" + action, json=data,
                                       allow_redirects=False,
                                       timeout=aiohttp.ClientTimeout(total=90 if action == "start" else 35)) as response:
                if response.status != 200:
                    raise RuntimeError("Local signaling HTTP " + str(response.status))
                return await response.json()

        async def render():
            nonlocal muted
            while not closing.is_set():
                for event in pygame.event.get():
                    if event.type == pygame.QUIT or (event.type == pygame.KEYDOWN and event.key == pygame.K_ESCAPE):
                        closing.set()
                    elif event.type == pygame.KEYDOWN and event.key == pygame.K_m:
                        muted = not muted
                        if muted and audio:
                            audio.stop()
                screen.fill((0, 0, 0))
                if latest is not None:
                    frame = latest
                    size = screen.get_size()
                    scale = min(size[0] / frame.get_width(), size[1] / frame.get_height())
                    w, h = max(1, int(frame.get_width() * scale)), max(1, int(frame.get_height() * scale))
                    screen.blit(pygame.transform.scale(frame, (w, h)), ((size[0]-w)//2, (size[1]-h)//2))
                else:
                    screen.blit(font.render(status, True, (235, 235, 235)), (24, 24))
                    screen.blit(font.render("Esc: disconnect   M: mute", True, (180, 180, 180)), (24, 64))
                pygame.display.flip()
                await asyncio.sleep(1 / FPS)

        async def consume(track):
            nonlocal latest
            resampler = av.AudioResampler(format="s16", layout="stereo", rate=48000) if track.kind == "audio" else None
            try:
                while not closing.is_set():
                    frame = await track.recv()
                    if track.kind == "video":
                        rgb = frame.to_ndarray(format="rgb24")
                        latest = pygame.image.frombuffer(rgb.tobytes(), (frame.width, frame.height), "RGB").copy()
                    elif audio and not muted:
                        for output in resampler.resample(frame):
                            sound = pygame.mixer.Sound(buffer=output.to_ndarray().tobytes())
                            if not audio.get_busy():
                                audio.play(sound)
                            elif audio.get_queue() is None:
                                audio.queue(sound)
                            # Never grow an unbounded playback queue.
            except asyncio.CancelledError:
                raise
            except Exception:
                if not closing.is_set():
                    print("RTC media track ended", file=sys.stderr)
                    closing.set()

        @pc.on("track")
        def on_track(track):
            tasks.append(asyncio.create_task(consume(track)))

        @pc.on("connectionstatechange")
        async def on_state():
            nonlocal status
            status = "RTC " + pc.connectionState
            if pc.connectionState in ("failed", "closed"):
                closing.set()

        video = pc.addTransceiver("video", direction="recvonly")
        video.setCodecPreferences([codec for codec in RTCRtpReceiver.getCapabilities("video").codecs
                                   if codec.mimeType.lower() in ("video/h264", "video/rtx")])
        sound = pc.addTransceiver("audio", direction="recvonly")
        sound.setCodecPreferences([codec for codec in RTCRtpReceiver.getCapabilities("audio").codecs
                                   if codec.mimeType.lower() == "audio/opus"])
        channels = {label: pc.createDataChannel(label, protocol=protocol,
                    ordered=label != "input", maxRetransmits=0 if label == "input" else None)
                    for label, protocol in CHANNELS}
        control, input_channel, message = (channels[x] for x in ("control", "input", "message"))

        @message.on("open")
        def message_open():
            channel_json(message, dict(type="Handshake", version="messageV1",
                         id=str(uuid.uuid4()), cv="0"))

        @message.on("message")
        def message_received(raw):
            nonlocal handshake
            if not isinstance(raw, str):
                return
            try:
                value = json.loads(raw)
            except ValueError:
                return
            if not isinstance(value, dict):
                return
            if value.get("type") == "HandshakeAck" and not handshake:
                handshake = True
                configs = [
                    ("/systemUi/configuration", dict(version=[0, 2, 0], systemUis=[])),
                    ("/properties/clientappinstallidchanged", dict(clientAppInstallId=str(uuid.uuid4()))),
                    ("/characteristics/orientationchanged", dict(orientation=0)),
                    ("/characteristics/touchinputenabledchanged", dict(touchInputEnabled=False)),
                    ("/characteristics/clientdevicecapabilities",
                     dict(supportsCustomResolution=True, supportsHevc=False, supportsHdr=False,
                          supportsFps=FPS, maxWidth=WIDTH, maxHeight=HEIGHT, maxBitrateKbps=8000,
                          video=dict(width=WIDTH, height=HEIGHT, maxWidth=WIDTH, maxHeight=HEIGHT, maxBitrateKbps=8000))),
                    ("/characteristics/dimensionschanged", dict(horizontal=WIDTH, vertical=HEIGHT,
                     preferredWidth=WIDTH, preferredHeight=HEIGHT, safeAreaLeft=0, safeAreaTop=0,
                     safeAreaRight=WIDTH, safeAreaBottom=HEIGHT, supportsCustomResolution=True)),
                ]
                for target, content in configs:
                    channel_json(message, envelope("/streaming" + target, content))
            if value.get("target") == "/streaming/sessionLifetimeManagement/serverInitiatedDisconnect":
                if isinstance(value.get("id"), str):
                    channel_json(message, dict(type="TransactionComplete", id=value["id"], content="", cv=""))
                closing.set()

        @control.on("open")
        def control_open():
            channel_json(control, dict(message="authorizationRequest",
                         accessKey="4BDB3609-C1F1-4195-9B37-FEFF45DA8B8E"))
            dimensions = dict(width=WIDTH, height=HEIGHT)
            channel_json(control, dict(message="VideoPreference", type="VideoPreference",
                         resolution=dimensions, dimensions=dimensions, maxFPS=FPS))

        @input_channel.on("open")
        def input_open():
            nonlocal sequence
            input_channel.send(header(8, sequence, now()) + b"\0")
            sequence += 1

        async def pads():
            nonlocal sequence
            previous = set()
            while not closing.is_set():
                if handshake and control.readyState == input_channel.readyState == "open":
                    try:
                        result = await asyncio.wait_for(request("controllers"), 1)
                        frames = {x["GamepadIndex"]: x for x in result.get("pads", [])
                                  if isinstance(x, dict) and x.get("GamepadIndex") in range(4)}
                    except (asyncio.TimeoutError, aiohttp.ClientError, RuntimeError):
                        frames = {}
                    slots = set(frames)
                    for slot in slots - previous:
                        channel_json(control, dict(message="gamepadChanged", gamepadIndex=slot, wasAdded=True))
                    for slot in previous - slots:
                        frames[slot] = {}  # release buttons before detaching
                    if frames and input_channel.bufferedAmount < 8192:
                        input_channel.send(pad_packet(sequence, frames, now()))
                        sequence += 1
                    for slot in previous - slots:
                        channel_json(control, dict(message="gamepadChanged", gamepadIndex=slot, wasAdded=False))
                    previous = slots
                await asyncio.sleep(.033)

        async def maintenance():
            nonlocal status
            last_keepalive = time.monotonic()
            failures = 0
            while not closing.is_set():
                await asyncio.sleep(5)
                channel_json(control, dict(message="videoKeyframeRequested", ifrRequested=True))
                try:
                    state = await request("state")
                    if state.get("state") in TERMINAL:
                        closing.set()
                    if time.monotonic() - last_keepalive >= 25:
                        await request("keepalive", {})
                        last_keepalive = time.monotonic()
                    failures = 0
                except (aiohttp.ClientError, asyncio.TimeoutError, RuntimeError):
                    failures += 1
                    if failures >= 3:
                        status = "Xbox service unavailable"
                        closing.set()

        async def negotiate():
            nonlocal started, status
            await request("start", {"titleId": title})
            started = True
            status = "Waiting for Xbox server..."
            transferred = False
            for _ in range(240):
                state = (await request("state")).get("state")
                if state == "Provisioned":
                    break
                if state in TERMINAL:
                    raise RuntimeError("Xbox session ended during provisioning")
                if state == "ReadyToConnect" and not transferred:
                    await request("connect", {})
                    transferred = True
                await asyncio.sleep(1)
            else:
                raise RuntimeError("Provisioning timed out")
            status = "Negotiating native RTC..."
            await pc.setLocalDescription(await pc.createOffer())
            sdp = pc.localDescription.sdp
            candidates = local_ice(sdp)
            if not candidates:
                raise RuntimeError("No local ICE candidates")
            await request("sdpPost", {"sdp": strip_ice(sdp)})
            for _ in range(60):
                result = unwrap(await request("sdpGet"))
                if isinstance(result, dict) and isinstance(result.get("sdp"), str):
                    answer = result["sdp"]
                    break
                await asyncio.sleep(.5)
            else:
                raise RuntimeError("No SDP answer")
            await pc.setRemoteDescription(RTCSessionDescription(sdp=answer, type="answer"))
            await request("icePost", {"candidates": candidates})
            for _ in range(60):
                entries = remote_ice(await request("iceGet"))
                if entries or local_ice(answer):
                    for entry in entries:
                        candidate = candidate_from_sdp(entry["candidate"].strip().removeprefix("a=").removeprefix("candidate:"))
                        candidate.sdpMid = entry.get("sdpMid")
                        candidate.sdpMLineIndex = entry.get("sdpMLineIndex")
                        await pc.addIceCandidate(candidate)
                    await pc.addIceCandidate(None)
                    break
                await asyncio.sleep(.5)
            else:
                raise RuntimeError("No remote ICE candidates")
            tasks.extend([asyncio.create_task(pads()), asyncio.create_task(maintenance())])
            for _ in range(45):
                if pc.connectionState == "connected":
                    return
                if closing.is_set():
                    return
                await asyncio.sleep(1)
            raise RuntimeError("RTC connection timed out")

        tasks.append(asyncio.create_task(render()))
        negotiation = asyncio.create_task(negotiate())
        close_wait = asyncio.create_task(closing.wait())
        try:
            done, _ = await asyncio.wait((negotiation, close_wait), return_when=asyncio.FIRST_COMPLETED)
            if negotiation in done:
                await negotiation
                await close_wait
        finally:
            closing.set()
            for task in tasks + [negotiation, close_wait]:
                task.cancel()
            await asyncio.gather(*tasks, negotiation, close_wait, return_exceptions=True)
            await pc.close()
            if started:
                try:
                    await asyncio.wait_for(request("stop", {}), 5)
                except Exception:
                    pass
            pygame.quit()


if __name__ == "__main__":
    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        pass
    except Exception as error:
        # No SDP, ICE addresses, local secret, title, or upstream bodies in logs.
        print("Native RTC failed: " + type(error).__name__, file=sys.stderr)
        sys.exit(1)
