package io.noeriva.control.applications;

import io.noeriva.control.ApiException;
import io.noeriva.control.SecurityConfiguration.Operator;
import java.util.List;
import org.junit.jupiter.api.Test;
import static io.noeriva.control.applications.ApplicationModels.*;
import static org.assertj.core.api.Assertions.*;

class ApplicationServiceValidationTest {
 @Test void rejectsTooFewRowsForSelectedInterfacesBeforeAnyCredentialOrStorageAccess(){
  var service=new ApplicationService(null,null,null,null,null,null);
  var admin=new Operator("admin","unused","synthetic-org",List.of("ADMIN"));
  assertThatThrownBy(()->service.save(admin,"synthetic-device",new SettingsInput(0,true,60,List.of(8,9),1)))
   .isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).code).isEqualTo("INVALID_APPLICATION_SETTINGS"));
 }
}
