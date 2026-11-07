import java.util.*;
import java.io.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class PolygonModifier {

    private static final String path = System.getProperty("polygon.dir", "data/polys") + "/";


    public static void integerPolygon(String filename){

        filename = path + filename + ".txt";

        List<Point> scaledPoints = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {

            String line;
            Pattern pattern = Pattern.compile("\\((-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)\\)");

            while ((line = br.readLine()) != null) {

                Matcher matcher = pattern.matcher(line);

                while (matcher.find()) {

                    double x = Double.parseDouble(matcher.group(1));
                    double y = Double.parseDouble(matcher.group(2));

                    // Scale
                    x = (int)(Math.round(x));
                    y = (int)(Math.round(y));

                    scaledPoints.add(new Point(x, y));
                }
            }

        } catch (IOException e) {
            System.out.println("Error reading file: " + e.getMessage());
            return;
        }

        // Write back to same file
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(filename))) {

            for (Point p : scaledPoints) {
                bw.write("(" + trim(p.x) + ", " + trim(p.y) + ")");
                bw.newLine();
            }

        } catch (IOException e) {
            System.out.println("Error writing file: " + e.getMessage());
        }

    }


    public static void scalePolygon(String filename, double scaleFactor) {

        filename = path + filename + ".txt";

        List<Point> scaledPoints = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {

            String line;
            Pattern pattern = Pattern.compile("\\((-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)\\)");

            while ((line = br.readLine()) != null) {

                Matcher matcher = pattern.matcher(line);

                while (matcher.find()) {

                    double x = Double.parseDouble(matcher.group(1));
                    double y = Double.parseDouble(matcher.group(2));

                    // Scale
                    x *= scaleFactor;
                    y *= scaleFactor;

                    scaledPoints.add(new Point(x, y));
                }
            }

        } catch (IOException e) {
            System.out.println("Error reading file: " + e.getMessage());
            return;
        }

        // Write back to same file
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(filename))) {

            for (Point p : scaledPoints) {
                bw.write("(" + trim(p.x) + ", " + trim(p.y) + ")");
                bw.newLine();
            }

        } catch (IOException e) {
            System.out.println("Error writing file: " + e.getMessage());
        }
    }
    private static String trim(double val) {
        if (Math.abs(val - Math.round(val)) < Point.EPS)
            return String.valueOf((long)Math.round(val));
        return String.valueOf(val);
    }

}