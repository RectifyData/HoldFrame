package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;
import dev.rectifydata.holdframe.core.ProjectIO;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Headless export for batch automation, Windows, Linux and macOS. */
public final class ExportCli {
    private ExportCli() {}
    private static String usage() {
        return "HoldFrame export (Java 17+)\n"+
          "java -jar HoldFrame.jar --export <project.holdframe> --format <mp4|gif|png|pngzip|sheet> --output <file> [--pose N] [--force] [--ffmpeg <binary>]\n"+
          "  mp4: H.264 video with audio tracks (requires FFmpeg on PATH or --ffmpeg)\n"+
          "  gif: animated GIF, silent, one image per held pose\n"+
          "  png: PNG still of a pose (0-based --pose index; defaults to first pose)\n"+
          "  pngzip: PNGs for each pose in a ZIP with timing.csv\n"+
          "  sheet: sprite sheet PNG (up to 32 poses)\n"+
          "Export never creates temporary frames; MP4 raw pixels stream into FFmpeg.\n";
    }
    public static int run(String[] args) {
        String source=null,format=null,output=null,ffmpeg=null;
        int pose=0;
        boolean overwrite=false;
        if (args.length==1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.print(usage()); return 0;
        }
        try {
            for (int i=0;i<args.length;i++) {
                String opt=args[i];
                if ("--force".equals(opt)) overwrite=true;
                else if (i+1<args.length) {
                    String value=args[++i];
                    switch(opt) {
                        case "--export" -> source=value;
                        case "--format" -> format=value.toLowerCase(Locale.ROOT);
                        case "--output" -> output=value;
                        case "--pose" -> pose=Integer.parseInt(value);
                        case "--ffmpeg" -> ffmpeg=value;
                        default -> throw new IllegalArgumentException("Unknown option: "+opt);
                    }
                } else throw new IllegalArgumentException("Missing value for "+opt);
            }
            if (source==null || format==null || output==null) throw new IllegalArgumentException("Missing required options");
            String suffix=switch(format) {
                case "mp4" -> ".mp4";
                case "gif" -> ".gif";
                case "pngzip" -> ".zip";
                case "sheet", "png" -> ".png";
                default -> throw new IllegalArgumentException("Invalid export format: "+format);
            };
            Path destination=Path.of(output).toAbsolutePath().normalize();
            if (!destination.toString().toLowerCase(Locale.ROOT).endsWith(suffix))
                throw new IllegalArgumentException("Output must have "+suffix+" extension");
            if (Files.exists(destination) && !overwrite)
                throw new IllegalArgumentException("Output exists; pass --force to overwrite");
            if (Files.isDirectory(destination)) throw new IllegalArgumentException("Destination is a directory");
            if (ffmpeg!=null) System.setProperty("holdframe.ffmpeg",ffmpeg);
            Path projectFile=Path.of(source).toAbsolutePath().normalize();
            Model.Project project;
            try (InputStream input=Files.newInputStream(projectFile)) {
                project=ProjectIO.read(input,projectFile.getParent());
            }
            final ExportSupport.Progress progress=(done,total)->{
                if (done==total || done%Math.max(1,total/10)==0)
                    System.out.printf(Locale.ROOT,"\rExporting %d / %d (%.0f%%)  ",done,total,100.0*done/total);
            };
            switch(format) {
                case "mp4" -> ExportSupport.mp4(project,destination,progress);
                case "gif" -> ExportSupport.gif(project,destination,progress);
                case "pngzip" -> ExportSupport.pngSequence(project,destination,progress);
                case "sheet" -> ExportSupport.spriteSheet(project,destination,progress);
                case "png" -> ExportSupport.png(project,pose,destination);
                default -> throw new IllegalArgumentException("Unsupported format");
            }
            System.out.println("\nExported: "+destination);
            return 0;
        } catch (Exception ex) {
            System.err.println("Export failed: "+ex.getMessage()+"\n"+usage());
            return 1;
        }
    }
}
