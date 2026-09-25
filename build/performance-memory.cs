using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

/// <summary>Win32 process-tree accounting for separate performance-v1 memory observations.</summary>
public static class PerformanceMemory
{
    [StructLayout(LayoutKind.Sequential)] struct Security { public int Length; public IntPtr Descriptor; public int Inherit; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] struct Startup { public int Size; public string Reserved, Desktop, Title; public int X, Y, XSize, YSize, XChars, YChars, Fill, Flags; public short Show, ReservedSize; public IntPtr ReservedPointer, Input, Output, Error; }
    [StructLayout(LayoutKind.Sequential)] struct ProcessInfo { public IntPtr Process, Thread; public uint Id, ThreadId; }
    [StructLayout(LayoutKind.Sequential)] struct Memory { public uint Size, PageFaults; public UIntPtr PeakWorkingSet, WorkingSet, PeakPagedPool, PagedPool, PeakNonPagedPool, NonPagedPool, Pagefile, PeakPagefile, Private; }
    [StructLayout(LayoutKind.Sequential)] struct BasicLimit { public long ProcessTime, JobTime; public uint Flags; public UIntPtr MinWorkingSet, MaxWorkingSet; public uint ActiveLimit; public UIntPtr Affinity; public uint Priority, Scheduling; }
    [StructLayout(LayoutKind.Sequential)] struct Io { public ulong ReadOps, WriteOps, OtherOps, ReadBytes, WriteBytes, OtherBytes; }
    [StructLayout(LayoutKind.Sequential)] struct ExtendedLimit { public BasicLimit Basic; public Io Io; public UIntPtr ProcessLimit, JobLimit, PeakProcess, PeakJob; }
    [StructLayout(LayoutKind.Sequential)] struct Accounting { public long UserTime, KernelTime, PeriodUser, PeriodKernel; public uint PageFaults, Total, Active, Terminated; }
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)] static extern IntPtr CreateJobObject(IntPtr attributes, string name);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool SetInformationJobObject(IntPtr job, int infoClass, ref ExtendedLimit info, uint length);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool QueryInformationJobObject(IntPtr job, int infoClass, IntPtr info, uint length, IntPtr returned);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)] static extern bool CreateProcess(string application, StringBuilder commandLine, IntPtr processAttributes, IntPtr threadAttributes, bool inherit, uint flags, IntPtr environment, string directory, ref Startup startup, out ProcessInfo process);
    [DllImport("kernel32.dll", SetLastError = true)] static extern uint ResumeThread(IntPtr thread);
    [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr OpenProcess(uint access, bool inherit, uint id);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool GetExitCodeProcess(IntPtr process, out uint code);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool TerminateJobObject(IntPtr job, uint code);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool TerminateProcess(IntPtr process, uint code);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool CloseHandle(IntPtr handle);
    [DllImport("psapi.dll", SetLastError = true)] static extern bool GetProcessMemoryInfo(IntPtr process, out Memory counters, uint size);
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)] static extern IntPtr CreateFile(string name, uint access, uint sharing, ref Security security, uint creation, uint flags, IntPtr template);

    /// <summary>Raw samples and final counters; callers must preserve these independently of derived gates.</summary>
    public sealed class Result
    {
        public double WallSeconds;
        public uint ExitCode;
        public ulong PeakPrivateBytes, PeakWorkingSetBytes, JobPeakPrivateBytes, ProcessPeakPrivateBytes, ProcessPeakWorkingSetBytes, ScratchPeakBytes;
        public uint TotalProcesses;
        public int ObservedProcesses;
        public string TracePath;
        public List<ProcessPeak> Processes = new List<ProcessPeak>();
    }

    /// <summary>Final Win32 peak counters retained separately for each observed member of the job.</summary>
    public sealed class ProcessPeak
    {
        public uint ProcessId;
        public ulong PrivateBytes, WorkingSetBytes;
    }

    /// <summary>Throws the original Win32 error immediately, before another interop call can replace it.</summary>
    static void Check(bool success) { if (!success) throw new Win32Exception(Marshal.GetLastWin32Error()); }

    /// <summary>Quotes one Windows argv token, including embedded quotes and trailing backslashes.</summary>
    public static string Quote(string argument)
    {
        if (argument == null || argument.IndexOf('\0') >= 0) throw new ArgumentException("Invalid command argument");
        var value = new StringBuilder("\"");
        int slashes = 0;
        foreach (char c in argument)
        {
            if (c == '\\') { slashes++; continue; }
            value.Append('\\', c == '"' ? 2 * slashes + 1 : slashes);
            value.Append(c);
            slashes = 0;
        }
        return value.Append('\\', 2 * slashes).Append('"').ToString();
    }

    /// <summary>Queries a fixed-size Job Object information structure and frees its native buffer.</summary>
    static T Query<T>(IntPtr job, int infoClass)
    {
        int size = Marshal.SizeOf<T>();
        IntPtr pointer = Marshal.AllocHGlobal(size);
        try { Check(QueryInformationJobObject(job, infoClass, pointer, (uint)size, IntPtr.Zero)); return Marshal.PtrToStructure<T>(pointer); }
        finally { Marshal.FreeHGlobal(pointer); }
    }

    /// <summary>Retains child handles so peak counters remain queryable after process termination.</summary>
    static void CaptureChildren(IntPtr job, Dictionary<uint, IntPtr> handles)
    {
        int size = 8 + 65536 * IntPtr.Size;
        IntPtr pointer = Marshal.AllocHGlobal(size);
        try
        {
            Check(QueryInformationJobObject(job, 3, pointer, (uint)size, IntPtr.Zero));
            int count = Marshal.ReadInt32(pointer, 4);
            for (int index = 0; index < count; index++)
            {
                uint id = (uint)Marshal.ReadIntPtr(pointer, 8 + index * IntPtr.Size).ToInt64();
                if (handles.ContainsKey(id)) continue;
                IntPtr handle = OpenProcess(0x0400 | 0x0010, false, id);
                Check(handle != IntPtr.Zero);
                handles.Add(id, handle);
            }
        }
        finally { Marshal.FreeHGlobal(pointer); }
    }

    /// <summary>Measures the process tree, launched suspended to prevent allocations outside the job.</summary>
    public static Result Run(string executable, string[] arguments, string directory, string traceDirectory, string scratchDirectory, int timeoutSeconds, Dictionary<string, string> processEnvironment)
    {
        if (!Environment.Is64BitProcess) throw new InvalidOperationException("Windows x64 harness required");
        Directory.CreateDirectory(traceDirectory);
        var result = new Result { TracePath = Path.Combine(traceDirectory, "memory.csv") };
        IntPtr job = IntPtr.Zero, output = IntPtr.Zero, error = IntPtr.Zero, input = IntPtr.Zero;
        IntPtr environmentBlock = IntPtr.Zero;
        var handles = new Dictionary<uint, IntPtr>();
        ProcessInfo process = new ProcessInfo();
        bool complete = false;
        try
        {
            job = CreateJobObject(IntPtr.Zero, null); Check(job != IntPtr.Zero);
            // Closing the job on any exception must also stop descendants; breakaway is never enabled.
            var limits = new ExtendedLimit { Basic = new BasicLimit { Flags = 0x2000 } };
            Check(SetInformationJobObject(job, 9, ref limits, (uint)Marshal.SizeOf<ExtendedLimit>()));
            var security = new Security { Length = Marshal.SizeOf<Security>(), Inherit = 1 };
            output = CreateFile(Path.Combine(traceDirectory, "stdout.txt"), 0x40000000, 1, ref security, 1, 0x80, IntPtr.Zero); Check(output != new IntPtr(-1));
            error = CreateFile(Path.Combine(traceDirectory, "stderr.txt"), 0x40000000, 1, ref security, 1, 0x80, IntPtr.Zero); Check(error != new IntPtr(-1));
            input = CreateFile("NUL", 0x80000000, 1, ref security, 3, 0x80, IntPtr.Zero); Check(input != new IntPtr(-1));
            var startup = new Startup { Size = Marshal.SizeOf<Startup>(), Flags = 0x100 | 1, Show = 0, Input = input, Output = output, Error = error };
            var command = new StringBuilder(String.Join(" ", new[] { executable }.Concat(arguments).Select(Quote)));
            var environment = new SortedDictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (System.Collections.DictionaryEntry pair in Environment.GetEnvironmentVariables()) environment[(string)pair.Key] = (string)pair.Value;
            foreach (var pair in processEnvironment) environment[pair.Key] = pair.Value;
            // A private Unicode block routes only this child; the harness environment is unchanged.
            environmentBlock = Marshal.StringToHGlobalUni(String.Join("\0", environment.Select(pair => pair.Key + "=" + pair.Value)) + "\0\0");
            using (var trace = new StreamWriter(result.TracePath, false, new UTF8Encoding(false)))
            {
                trace.WriteLine("elapsed_seconds,private_committed_bytes,working_set_bytes,scratch_bytes,active_processes");
                var watch = Stopwatch.StartNew();
                Check(CreateProcess(executable, command, IntPtr.Zero, IntPtr.Zero, true, 4 | 0x08000000 | 0x400, environmentBlock, directory, ref startup, out process));
                handles.Add(process.Id, process.Process);
                Check(AssignProcessToJobObject(job, process.Process));
                Check(ResumeThread(process.Thread) != uint.MaxValue);
                double nextSample = 0;
                while (true)
                {
                    CaptureChildren(job, handles);
                    ulong privateBytes = 0, workingSet = 0;
                    foreach (var handle in handles.Values)
                    {
                        Memory memory;
                        Check(GetProcessMemoryInfo(handle, out memory, (uint)Marshal.SizeOf<Memory>()));
                        privateBytes += memory.Private.ToUInt64();
                        workingSet += memory.WorkingSet.ToUInt64();
                        result.ProcessPeakPrivateBytes = Math.Max(result.ProcessPeakPrivateBytes, memory.PeakPagefile.ToUInt64());
                        result.ProcessPeakWorkingSetBytes = Math.Max(result.ProcessPeakWorkingSetBytes, memory.PeakWorkingSet.ToUInt64());
                    }
                    ulong scratch = 0;
                    if (!String.IsNullOrEmpty(scratchDirectory))
                        foreach (var path in Directory.EnumerateFiles(scratchDirectory, "*", SearchOption.AllDirectories)) scratch += (ulong)new FileInfo(path).Length;
                    result.ScratchPeakBytes = Math.Max(result.ScratchPeakBytes, scratch);
                    result.PeakPrivateBytes = Math.Max(result.PeakPrivateBytes, privateBytes);
                    result.PeakWorkingSetBytes = Math.Max(result.PeakWorkingSetBytes, workingSet);
                    var accounting = Query<Accounting>(job, 1);
                    trace.WriteLine(String.Join(",", watch.Elapsed.TotalSeconds.ToString("R", System.Globalization.CultureInfo.InvariantCulture), privateBytes, workingSet, scratch, accounting.Active));
                    if (accounting.Active == 0)
                    {
                        result.WallSeconds = watch.Elapsed.TotalSeconds;
                        result.TotalProcesses = accounting.Total;
                        break;
                    }
                    if (watch.Elapsed.TotalSeconds > timeoutSeconds) throw new TimeoutException("Memory observation timed out");
                    nextSample += 0.050;
                    int delay = (int)Math.Ceiling((nextSample - watch.Elapsed.TotalSeconds) * 1000);
                    if (delay > 0) Thread.Sleep(delay);
                }
            }
            result.ObservedProcesses = handles.Count;
            // A child that exits between polls cannot supply a final working-set counter: fail closed.
            if (result.TotalProcesses != result.ObservedProcesses) throw new InvalidOperationException("Missing final peak counters for an unobserved child process");
            var final = Query<ExtendedLimit>(job, 9);
            result.JobPeakPrivateBytes = final.PeakJob.ToUInt64();
            result.ProcessPeakPrivateBytes = 0;
            result.ProcessPeakWorkingSetBytes = 0;
            foreach (var pair in handles)
            {
                Memory memory;
                Check(GetProcessMemoryInfo(pair.Value, out memory, (uint)Marshal.SizeOf<Memory>()));
                if (memory.PeakPagefile.ToUInt64() == 0 || memory.PeakWorkingSet.ToUInt64() == 0)
                    throw new InvalidOperationException("Missing final peak counter for process " + pair.Key);
                result.Processes.Add(new ProcessPeak { ProcessId = pair.Key, PrivateBytes = memory.PeakPagefile.ToUInt64(), WorkingSetBytes = memory.PeakWorkingSet.ToUInt64() });
                result.ProcessPeakPrivateBytes += memory.PeakPagefile.ToUInt64();
                result.ProcessPeakWorkingSetBytes += memory.PeakWorkingSet.ToUInt64();
            }
            // Summed per-process peaks conservatively include child spikes between scheduled samples.
            result.PeakPrivateBytes = Math.Max(result.PeakPrivateBytes, Math.Max(result.JobPeakPrivateBytes, result.ProcessPeakPrivateBytes));
            result.PeakWorkingSetBytes = Math.Max(result.PeakWorkingSetBytes, result.ProcessPeakWorkingSetBytes);
            Check(GetExitCodeProcess(process.Process, out result.ExitCode));
            if (result.JobPeakPrivateBytes == 0 || result.ProcessPeakPrivateBytes == 0 || result.ProcessPeakWorkingSetBytes == 0) throw new InvalidOperationException("Missing final peak memory counter");
            complete = true;
            return result;
        }
        finally
        {
            if (!complete && job != IntPtr.Zero) TerminateJobObject(job, 1);
            // Assignment itself can fail, leaving a suspended process outside the job.
            if (!complete && process.Process != IntPtr.Zero) TerminateProcess(process.Process, 1);
            if (process.Thread != IntPtr.Zero) CloseHandle(process.Thread);
            foreach (var handle in handles.Values) CloseHandle(handle);
            if (job != IntPtr.Zero) CloseHandle(job);
            if (environmentBlock != IntPtr.Zero) Marshal.FreeHGlobal(environmentBlock);
            foreach (var handle in new[] { output, error, input }) if (handle != IntPtr.Zero && handle != new IntPtr(-1)) CloseHandle(handle);
        }
    }
}
