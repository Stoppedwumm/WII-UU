import importlib.util
import pathlib
import struct
import unittest

path = pathlib.Path(__file__).resolve().parents[2] / "resources/xcloud/native-player.py"
spec = importlib.util.spec_from_file_location("native_player", path)
player = importlib.util.module_from_spec(spec)
spec.loader.exec_module(player)


class NativeRtcTest(unittest.TestCase):
    def test_pad_packet(self):
        packet = player.pad_packet(7, {2: {"A": 1, "LeftThumbYAxis": -1, "LeftTrigger": 1}}, 25)
        self.assertEqual(len(packet), 38)
        self.assertEqual(struct.unpack_from("<HId", packet), (2, 7, 25))
        self.assertEqual(packet[14:16], b"\x01\x02")
        self.assertEqual(struct.unpack_from("<HhhhhHHI", packet, 16),
                         (16, 0, 32767, 0, 0, 65535, 0, 1))
        self.assertEqual(packet[-4:], b"\0\0\0\1")

    def test_ice(self):
        sdp = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 102\r\na=mid:0\r\na=candidate:1 1 udp 1 127.0.0.1 1234 typ host\r\na=end-of-candidates\r\n"
        candidates = player.local_ice(sdp)
        self.assertEqual(candidates[0]["sdpMid"], "0")
        self.assertEqual(candidates[0]["sdpMLineIndex"], 0)
        self.assertNotIn("candidate", player.strip_ice(sdp))
        self.assertEqual(player.remote_ice({"iceCandidates": [None, {}, *candidates]}), candidates)

    def test_neutral_release(self):
        packet = player.pad_packet(0, {0: {}}, 0)
        self.assertEqual(packet[16:30], b"\0" * 14)


if __name__ == "__main__":
    unittest.main()
