"""Phase 0C C4 regression tests for the frozen server checkout.

Copy this file to main/xiaozhi-server/tests/core/handle/ before applying the
companion patch. It intentionally isolates the hello handler from optional
provider dependencies.
"""

import ast
import copy
import json
import sys
from enum import Enum
from pathlib import Path
from types import ModuleType, SimpleNamespace

import pytest


def _stub(name, **members):
    module = ModuleType(name)
    for key, value in members.items():
        setattr(module, key, value)
    sys.modules[name] = module


class _WakeupWordsConfig:
    pass


class _SentenceType(Enum):
    FIRST = "first"
    LAST = "last"


async def _async_noop(*_args, **_kwargs):
    return None


_stub("core.utils.dialogue", Message=object)
_stub(
    "core.utils.util",
    audio_to_data=_async_noop,
    remove_punctuation_and_length=lambda text: (0, text),
    opus_datas_to_wav_bytes=lambda *_args, **_kwargs: b"",
)
_stub("core.providers.tts.dto.dto", SentenceType=_SentenceType)
_stub("core.utils.wakeup_word", WakeupWordsConfig=_WakeupWordsConfig)
_stub(
    "core.handle.sendAudioHandle",
    sendAudioMessage=_async_noop,
    send_tts_message=_async_noop,
)
_stub(
    "core.providers.tools.device_mcp",
    MCPClient=object,
    send_mcp_initialize_message=_async_noop,
)

from core.handle.helloHandle import handleHelloMessage


SERVER_AUDIO = {
    "format": "opus", "sample_rate": 24000, "channels": 1, "frame_duration": 60
}
CLIENT_AUDIO = {
    "format": "opus", "sample_rate": 16000, "channels": 1, "frame_duration": 60
}


class _Logger:
    def bind(self, **_kwargs):
        return self

    def debug(self, _message):
        return None


class _WebSocket:
    def __init__(self):
        self.sent = []

    async def send(self, payload):
        self.sent.append(json.loads(payload))


def _connection():
    return SimpleNamespace(
        logger=_Logger(),
        websocket=_WebSocket(),
        welcome_msg={
            "type": "hello",
            "transport": "websocket",
            "session_id": "session-contract-test",
            "audio_params": copy.deepcopy(SERVER_AUDIO),
        },
        audio_format="opus",
        client_audio_format="opus",
        client_sample_rate=16000,
        client_channels=1,
        client_frame_duration=60,
        features=None,
    )


async def _hello(conn, audio_params=CLIENT_AUDIO):
    message = {"type": "hello", "version": 1, "transport": "websocket"}
    if audio_params is not None:
        message["audio_params"] = audio_params
    await handleHelloMessage(conn, message)
    return conn.websocket.sent[-1]


@pytest.mark.asyncio
async def test_client_16k_server_24k_advertises_24k_playback():
    assert (await _hello(_connection()))["audio_params"] == SERVER_AUDIO


@pytest.mark.asyncio
async def test_valid_client_uplink_is_stored_separately():
    conn = _connection()
    await _hello(conn)
    assert (
        conn.client_audio_format,
        conn.client_sample_rate,
        conn.client_channels,
        conn.client_frame_duration,
    ) == ("opus", 16000, 1, 60)


@pytest.mark.asyncio
async def test_client_hello_does_not_mutate_server_playback_config():
    conn = _connection()
    before = copy.deepcopy(conn.welcome_msg["audio_params"])
    await _hello(conn)
    assert conn.welcome_msg["audio_params"] == before


@pytest.mark.asyncio
async def test_server_hello_keeps_session_id():
    assert (await _hello(_connection()))["session_id"] == "session-contract-test"


@pytest.mark.asyncio
async def test_missing_client_audio_params_retains_defaults():
    conn = _connection()
    assert (await _hello(conn, None))["audio_params"] == SERVER_AUDIO
    assert conn.client_sample_rate == 16000


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "malformed",
    [
        "not-an-object",
        {"format": "pcm", "sample_rate": 12345, "channels": 99, "frame_duration": -1},
        {"format": "opus", "sample_rate": "16000", "channels": 1, "frame_duration": 60},
    ],
)
async def test_malformed_or_unsupported_audio_params_retain_defaults(malformed):
    conn = _connection()
    assert (await _hello(conn, malformed))["audio_params"] == SERVER_AUDIO
    assert (
        conn.client_audio_format,
        conn.client_sample_rate,
        conn.client_channels,
        conn.client_frame_duration,
    ) == ("opus", 16000, 1, 60)


def _load_methods(file_path, class_name, names, globals_dict):
    tree = ast.parse(file_path.read_text(encoding="utf-8"))
    source_class = next(
        node for node in tree.body
        if isinstance(node, ast.ClassDef) and node.name == class_name
    )
    methods = [
        node for node in source_class.body
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
        and node.name in names
    ]
    harness = ast.ClassDef(
        name="_ContractHarness", bases=[], keywords=[], body=methods, decorator_list=[]
    )
    module = ast.fix_missing_locations(ast.Module(body=[harness], type_ignores=[]))
    namespace = dict(globals_dict)
    exec(compile(module, str(file_path), "exec"), namespace)
    return namespace["_ContractHarness"]


def test_uplink_decoder_uses_client_contract_not_downlink_rate():
    calls = []

    class Decoder:
        def __init__(self, sample_rate, channels):
            calls.append(("init", sample_rate, channels))

        def decode(self, packet, frame_size):
            calls.append(("decode", packet, frame_size))
            return b"pcm"

    root = Path(__file__).parents[3]
    cls = _load_methods(
        root / "core" / "connection.py",
        "ConnectionHandler",
        {"_init_connection_state", "_decode_opus_packet"},
        {"opuslib_next": SimpleNamespace(Decoder=Decoder), "TAG": "contract-test"},
    )
    conn = cls()
    conn.client_sample_rate = 16000
    conn.client_channels = 1
    conn.client_frame_duration = 60
    conn.sample_rate = 24000
    conn.logger = _Logger()
    assert conn._decode_opus_packet(b"opus") == b"pcm"
    assert calls == [("init", 16000, 1), ("decode", b"opus", 960)]


@pytest.mark.asyncio
async def test_tts_encoder_rate_matches_server_hello_playback_rate():
    calls = []

    class Encoder:
        def __init__(self, sample_rate, channels, frame_size_ms):
            self.sample_rate = sample_rate
            calls.append((sample_rate, channels, frame_size_ms))

    class Thread:
        def __init__(self, **_kwargs):
            pass

        def start(self):
            pass

    root = Path(__file__).parents[3]
    cls = _load_methods(
        root / "core" / "providers" / "tts" / "base.py",
        "TTSProviderBase",
        {"open_audio_channels"},
        {
            "opus_encoder_utils": SimpleNamespace(OpusEncoderUtils=Encoder),
            "threading": SimpleNamespace(Thread=Thread),
        },
    )
    provider = cls()
    provider.tts_text_priority_thread = lambda: None
    provider._audio_play_priority_thread = lambda: None
    conn = SimpleNamespace(sample_rate=24000)
    await provider.open_audio_channels(conn)
    assert calls == [(24000, 1, 60)]
    provider.opus_encoder.sample_rate = 16000
    with pytest.raises(ValueError, match="does not match Server Hello"):
        await provider.open_audio_channels(conn)
