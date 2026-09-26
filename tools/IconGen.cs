using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;

// 生成各密度启动图标 PNG，避免依赖任何外部图像工具
internal static class IconGen
{
    private static readonly Color GradStart = Color.FromArgb(255, 76, 91, 212);   // #4C5BD4
    private static readonly Color GradEnd = Color.FromArgb(255, 142, 91, 240);    // #8E5BF0

    private static readonly float[][] Bolt =
    {
        new[] { 59.5f, 26f }, new[] { 40f, 57f }, new[] { 51f, 57f },
        new[] { 45.5f, 82f }, new[] { 68f, 49.5f }, new[] { 56f, 49.5f }
    };

    private static readonly float[][] ArrowLeft =
    {
        new[] { 28f, 36f }, new[] { 40f, 36f }, new[] { 40f, 32f }, new[] { 28f, 32f },
        new[] { 28f, 28f }, new[] { 19f, 34f }, new[] { 28f, 40f }
    };

    private static readonly float[][] ArrowRight =
    {
        new[] { 80f, 72f }, new[] { 68f, 72f }, new[] { 68f, 76f }, new[] { 80f, 76f },
        new[] { 80f, 80f }, new[] { 89f, 74f }, new[] { 80f, 68f }
    };

    private static PointF[] Scaled(float[][] pts, float k)
    {
        var result = new PointF[pts.Length];
        for (int i = 0; i < pts.Length; i++)
        {
            result[i] = new PointF(pts[i][0] * k, pts[i][1] * k);
        }
        return result;
    }

    private static GraphicsPath RoundedRect(float x, float y, float w, float h, float r)
    {
        var path = new GraphicsPath();
        float d = r * 2f;
        path.AddArc(x, y, d, d, 180, 90);
        path.AddArc(x + w - d, y, d, d, 270, 90);
        path.AddArc(x + w - d, y + h - d, d, d, 0, 90);
        path.AddArc(x, y + h - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }

    private static void Render(int size, bool round, string path)
    {
        using (var bmp = new Bitmap(size, size, PixelFormat.Format32bppArgb))
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
            g.PixelOffsetMode = PixelOffsetMode.HighQuality;
            g.Clear(Color.Transparent);

            float pad = size * 0.02f;
            float side = size - 2f * pad;

            // 背景裁剪形状
            GraphicsPath shape = round
                ? EllipsePath(pad, pad, side, side)
                : RoundedRect(pad, pad, side, side, size * 0.22f);

            // 渐变填充
            using (var brush = new LinearGradientBrush(
                       new PointF(0, 0), new PointF(size, size), GradStart, GradEnd))
            {
                g.FillPath(brush, shape);
            }

            float k = size / 108f;
            using (var white = new SolidBrush(Color.FromArgb(255, 255, 255)))
            using (var soft = new SolidBrush(Color.FromArgb(236, 255, 255, 255)))
            {
                g.FillPolygon(white, Scaled(Bolt, k));
                g.FillPolygon(soft, Scaled(ArrowLeft, k));
                g.FillPolygon(soft, Scaled(ArrowRight, k));
            }

            shape.Dispose();

            string dir = Path.GetDirectoryName(path);
            if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir))
            {
                Directory.CreateDirectory(dir);
            }
            bmp.Save(path, ImageFormat.Png);
        }
    }

    private static GraphicsPath EllipsePath(float x, float y, float w, float h)
    {
        var path = new GraphicsPath();
        path.AddEllipse(x, y, w, h);
        return path;
    }

    public static int Main(string[] args)
    {
        if (args.Length < 1)
        {
            Console.Error.WriteLine("usage: IconGen <res-dir>");
            return 2;
        }

        string res = args[0];
        var densities = new[]
        {
            new object[] { "mdpi", 48 },
            new object[] { "hdpi", 72 },
            new object[] { "xhdpi", 96 },
            new object[] { "xxhdpi", 144 },
            new object[] { "xxxhdpi", 192 }
        };

        foreach (var d in densities)
        {
            string name = (string)d[0];
            int size = (int)d[1];
            Render(size, false, Path.Combine(res, "mipmap-" + name, "ic_launcher.png"));
            Render(size, true, Path.Combine(res, "mipmap-" + name, "ic_launcher_round.png"));
            Console.WriteLine("generated mipmap-" + name + " (" + size + "x" + size + ")");
        }
        Console.WriteLine("DONE");
        return 0;
    }
}
