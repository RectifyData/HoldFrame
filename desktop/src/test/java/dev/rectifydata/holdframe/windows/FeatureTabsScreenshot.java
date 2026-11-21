package dev.rectifydata.holdframe.windows;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.JTabbedPane;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
public final class FeatureTabsScreenshot {
    @SuppressWarnings("unchecked")
    private static <T> T find(Component v,Class<T> t) {
        if(t.isInstance(v))return (T)v;
        if(v instanceof Container)for(Component e:((Container)v).getComponents()){
            T z=find(e,t);if(z!=null)return z;
        }return null;
    }
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(()->{
            Editor e=new Editor();
            try {
                e.setSize(1080,720);e.setVisible(true);
                JTabbedPane tabs=find(e.getContentPane(),JTabbedPane.class);
                if(tabs==null||tabs.getTabCount()!=4)throw new AssertionError("No tabs");
                e.validate();
                BufferedImage picture=new BufferedImage(e.getWidth(),e.getHeight(),BufferedImage.TYPE_INT_RGB);
                Graphics2D g=picture.createGraphics();e.paintAll(g);g.dispose();
                ImageIO.write(picture,"png",new File("/mnt/data/HoldFrame-v0.5.1-Tabs.png"));
            }catch(Exception ex){throw new RuntimeException(ex);}
            finally{e.dispose();}
        });
    }
}
