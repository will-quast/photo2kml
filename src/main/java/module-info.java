module com.williamquast {
    requires org.slf4j;
    requires org.apache.logging.log4j;
    requires java.desktop;
    requires javafx.controls;
    requires javafx.fxml;
    opens com.williamquast to javafx.fxml;

    exports com.williamquast;
}
