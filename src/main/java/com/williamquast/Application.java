package com.williamquast;


import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.awt.*;
import java.io.IOException;
import java.net.URL;

public class Application extends javafx.application.Application {



    public static void main(String[] args) {
        System.out.println("Starting Application");
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        try {

            FXMLLoader loader = new FXMLLoader();
            Parent root = loader.load(getClass().getResourceAsStream("/main.fxml"));

            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            int height = screenSize.height * 2 / 3;
            int width = screenSize.width * 2 / 3;
            Scene scene = new Scene(root, width, height);

            primaryStage.setTitle("Photo2Kml");
            primaryStage.setScene(scene);
            primaryStage.show();

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }


}
