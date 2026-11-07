import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class MetaData {

    private String fileName;

    public MetaData(String fileName){
        this.fileName = fileName + ".txt";
    }

    public void createLogFile() {
        try {
            File file = new File(fileName);

            // Create file if it doesn't exist
            if (!file.exists()) {
                file.createNewFile();
                System.out.println("File created: " + file.getName());
            }

            // Clear file contents (overwrite mode)
            FileWriter writer = new FileWriter(fileName, false);
            writer.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Works like System.out.print (no new line)
    public void print(String msg) {
        try (FileWriter writer = new FileWriter(fileName, true)) {
            writer.write(msg);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Works like System.out.println (adds new line)
    public void println(String msg) {
        try (FileWriter writer = new FileWriter(fileName, true)) {
            writer.write(msg + System.lineSeparator());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}