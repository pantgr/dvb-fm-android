using System;
using System.IO;
using System.Threading;
using NAudio.CoreAudioApi;
using NAudio.Wave;

// WASAPI loopback 60s από το default render device (SB Z) → f32 interleaved.
// Σκοπός: να δούμε αν το CD-app FM αφήνει pilot/57k στο audio (MPX tap trick).
class Program
{
    static void Main()
    {
        var dev = new MMDeviceEnumerator().GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        Console.WriteLine($"device: {dev.FriendlyName}");
        using var cap = new WasapiLoopbackCapture(dev);
        var wf = cap.WaveFormat;
        Console.WriteLine($"mix format: {wf.SampleRate} Hz, {wf.Channels} ch, {wf.BitsPerSample} bit, {wf.Encoding}");
        if (wf.SampleRate < 120000)
            Console.WriteLine("ΣΗΜΕΙΩΣΗ: <120kHz = το 57k ΔΕΝ χωράει (Nyquist) — μόνο pilot/38k ορατά. Για 57k: Windows sound settings → SB Z → 24bit/192000.");
        var fs = new FileStream(@"C:\Claude\dvb_android\sbz.f32", FileMode.Create);
        long total = 0;
        cap.DataAvailable += (s, e) => { fs.Write(e.Buffer, 0, e.BytesRecorded); total += e.BytesRecorded; };
        var done = new ManualResetEvent(false);
        cap.RecordingStopped += (s, e) => { fs.Close(); done.Set(); };
        cap.StartRecording();
        Console.WriteLine("recording 60s...");
        Thread.Sleep(60000);
        cap.StopRecording();
        done.WaitOne(3000);
        Console.WriteLine($"done: {total} bytes -> C:\\Claude\\dvb_android\\sbz.f32 (rate={wf.SampleRate}, ch={wf.Channels})");
    }
}
